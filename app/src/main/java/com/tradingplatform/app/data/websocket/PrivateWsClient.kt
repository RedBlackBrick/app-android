package com.tradingplatform.app.data.websocket

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.tradingplatform.app.BuildConfig
import com.tradingplatform.app.domain.model.WsConnectionState
import com.tradingplatform.app.domain.repository.AuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import timber.log.Timber
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.pow

/**
 * Client WebSocket privé vers `wss://{vps}/v1/ws/private`.
 *
 * ## Protocole d'authentification
 * Après l'ouverture de la connexion, envoie immédiatement :
 * `{"token": "<jwt_websocket>"}` comme premier message.
 * Le JWT est obtenu via `POST /v1/auth/ws-token` (claim `type=websocket`).
 *
 * ## Messages entrants
 * Tous les messages ont la forme `{"type": "...", "data": {...}, "timestamp": "..."}`.
 * Les types reconnus sont mappés sur [WsEvent]. Les types inconnus sont ignorés silencieusement.
 *
 * ## Ping/Pong
 * Sur réception d'un `ping`, répond `{"type": "pong"}` (heartbeat applicatif OkHttp).
 *
 * ## Reconnexion
 * Backoff exponentiel : 5s → 10s → 20s → … → 300s (max).
 * La reconnexion n'a lieu que si l'app est en foreground ([isAppForeground]).
 *
 * ## Thread-safety
 * [_events] est un [MutableSharedFlow] — safe pour émissions depuis n'importe quel thread.
 * Le WebSocket OkHttp rappelle les méthodes [WebSocketListener] depuis ses propres threads.
 *
 * @param okHttpClient Client principal (cert pinning + VPN check via intercepteurs).
 * @param authRepository Pour obtenir le token WS via `getWsToken()`.
 * @param appScope Scope applicatif (@Singleton) pour les coroutines longue durée.
 * @param baseUrl URL de base du VPS (ex: `https://10.42.0.1:443`).
 */
@Singleton
class PrivateWsClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val authRepository: AuthRepository,
    private val appScope: CoroutineScope,
    @Named("base_url") private val baseUrl: String,
) : DefaultLifecycleObserver {

    // ── SharedFlow des événements ──────────────────────────────────────────────
    private val _events = MutableSharedFlow<WsEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    val events: SharedFlow<WsEvent> = _events.asSharedFlow()

    // ── Etat de connexion expose a l'UI (F5) ────────────────────────────────────
    private val _connectionState = MutableStateFlow(WsConnectionState.Disconnected)
    val connectionState: StateFlow<WsConnectionState> = _connectionState.asStateFlow()

    // ── État interne ───────────────────────────────────────────────────────────
    @Volatile private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)

    /** Compteur de tentatives consécutives — utilisé pour le backoff exponentiel. */
    private val reconnectAttempts = AtomicInteger(0)

    /** Job du timer de reconnexion en cours — annulé si connect() est rappelé. */
    @Volatile private var reconnectJob: Job? = null

    /** Job du refresh proactif du token WS — annulé à la déconnexion. */
    @Volatile private var tokenRefreshJob: Job? = null

    /** True si l'app est visible (foreground). Mis à jour par ProcessLifecycleOwner. */
    @Volatile private var isAppForeground = false

    /**
     * Expiration du token WS en cours — utilisé pour détecter si le token a expiré
     * pendant que l'app était en background (R2 race condition fix).
     * Mis à jour dans [WsListener.onOpen] et [refreshWsToken].
     */
    @Volatile private var currentTokenExpiresAt: Instant? = null

    // URL WebSocket dérivée de baseUrl : https://… → wss://…/ws/private
    private val wsUrl: String
        get() = baseUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
            .trimEnd('/') + "/ws/private"

    companion object {
        private const val TAG = "PrivateWsClient"
        private const val BACKOFF_INITIAL_MS = 5_000L
        private const val BACKOFF_MAX_MS = 300_000L
        private const val BACKOFF_MULTIPLIER = 2.0

        /** Refresh le token WS à 80% de son TTL (même stratégie que le frontend SvelteKit). */
        private const val TOKEN_REFRESH_RATIO = 0.80

        /** Délai minimum avant un refresh proactif (10 secondes). */
        private const val TOKEN_REFRESH_MIN_MS = 10_000L
    }

    // ── Lifecycle app ──────────────────────────────────────────────────────────

    init {
        // Enregistrer l'observateur sur le thread principal (ProcessLifecycleOwner l'exige).
        // Dispatchers.Main.immediate : si on est déjà sur Main, exécution synchrone immédiate
        // (pas de dispatch supplémentaire), sinon dispatch normal. Non-bloquant dans tous les cas.
        appScope.launch(Dispatchers.Main.immediate) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this@PrivateWsClient)
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        isAppForeground = true

        if (isConnected.get()) {
            // La connexion est encore ouverte — vérifier si le token WS a expiré
            // pendant le background. Si oui, le serveur va fermer avec 4001 à tout moment.
            // Proactive refresh évite le backoff exponentiel (5-300s sans données).
            val expiresAt = currentTokenExpiresAt
            if (expiresAt != null && Instant.now().isAfter(expiresAt)) {
                Timber.tag(TAG).d("onStart — WS token expired during background, refreshing proactively")
                appScope.launch(Dispatchers.IO) { refreshWsToken() }
            } else {
                // Token encore valide — relancer le timer de refresh proactif
                // (il a pu être annulé ou expirer pendant le background)
                if (expiresAt != null) {
                    scheduleTokenRefresh(expiresAt)
                }
            }
        } else if (!isConnecting.get()) {
            // Pas connecté — reconnecter normalement
            connect()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        isAppForeground = false
        // App en background : annuler les timers pour économiser la batterie.
        // La connexion existante reste ouverte — elle sera fermée par le serveur (idle timeout).
        reconnectJob?.cancel()
        reconnectJob = null
        tokenRefreshJob?.cancel()
        tokenRefreshJob = null
        Timber.tag(TAG).d("App went to background — reconnect and token refresh timers cancelled")
    }

    // ── API publique ───────────────────────────────────────────────────────────

    /**
     * Initie la connexion WebSocket privée.
     *
     * Si une connexion est déjà en cours ou établie, l'appel est ignoré.
     * La connexion est lancée dans le scope applicatif (survit au ViewModel).
     */
    fun connect() {
        if (isConnected.get() || isConnecting.get()) {
            Timber.tag(TAG).d("connect() — already connected or connecting, skipping")
            return
        }
        _connectionState.value = WsConnectionState.Connecting
        appScope.launch(Dispatchers.IO) { openWebSocket() }
    }

    /**
     * Ferme la connexion proprement (code 1000 Normal Closure).
     * Annule le timer de reconnexion — ne se reconnecte pas automatiquement.
     */
    fun disconnect() {
        Timber.tag(TAG).d("disconnect() — closing WebSocket gracefully")
        tokenRefreshJob?.cancel()
        tokenRefreshJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts.set(0)
        currentTokenExpiresAt = null
        webSocket?.close(1000, "Client disconnecting")
        webSocket = null
        isConnected.set(false)
        isConnecting.set(false)
        _connectionState.value = WsConnectionState.Disconnected
    }

    // ── Connexion interne ──────────────────────────────────────────────────────

    private suspend fun openWebSocket() {
        if (!isConnecting.compareAndSet(false, true)) return

        // Obtenir le token WS + expiration (POST /v1/auth/ws-token)
        val wsTokenInfo = authRepository.getWsToken()
            .onFailure { e ->
                Timber.tag(TAG).w(e, "Failed to fetch WS token — will retry")
                isConnecting.set(false)
                scheduleReconnect()
            }
            .getOrNull() ?: return

        try {
            val request = Request.Builder()
                .url(wsUrl)
                .build()

            Timber.tag(TAG).d("Opening WS connection to $wsUrl")
            webSocket = okHttpClient.newWebSocket(request, WsListener(wsTokenInfo.token, wsTokenInfo.expiresAt))
            // isConnecting reste true jusqu'à onOpen ou onFailure du WsListener
        } catch (e: Exception) {
            // Rethrow CancellationException to preserve structured concurrency — catching it
            // would prevent coroutine cancellation from propagating correctly.
            if (e is kotlinx.coroutines.CancellationException) throw e
            // newWebSocket() peut échouer immédiatement (ex: IllegalStateException si le client
            // est shutdown, ou SecurityException). Sans ce catch, isConnecting reste true → plus
            // aucune reconnexion possible.
            Timber.tag(TAG).w(e, "openWebSocket() failed immediately — resetting isConnecting")
            isConnecting.set(false)
            scheduleReconnect()
        }
    }

    // ── Reconnexion avec backoff ───────────────────────────────────────────────

    private fun scheduleReconnect() {
        if (!isAppForeground) {
            Timber.tag(TAG).d("scheduleReconnect() — app in background, skipping")
            return
        }

        reconnectJob?.cancel()
        val attempts = reconnectAttempts.getAndIncrement()
        val delayMs = minOf(
            (BACKOFF_INITIAL_MS * BACKOFF_MULTIPLIER.pow(attempts)).toLong(),
            BACKOFF_MAX_MS,
        )

        Timber.tag(TAG).d("Scheduling reconnect in ${delayMs}ms (attempt #${attempts + 1})")
        // Transition a Connecting pour l'UI (F5) — le debounce ViewModel evite le flicker
        _connectionState.value = WsConnectionState.Connecting
        reconnectJob = appScope.launch(Dispatchers.IO) {
            delay(delayMs)
            if (isAppForeground && !isConnected.get() && !isConnecting.get()) {
                openWebSocket()
            }
        }
    }

    // ── Refresh proactif du token WS ──────────────────────────────────────────

    /**
     * Planifie un refresh proactif du token WS à 80% de son TTL.
     *
     * Même stratégie que le frontend SvelteKit : envoyer un message
     * `{"action": "refresh_token", "token": "<new_jwt>"}` sur le WebSocket existant,
     * sans avoir à se reconnecter.
     *
     * @param expiresAt Instant d'expiration du token WS courant.
     */
    private fun scheduleTokenRefresh(expiresAt: Instant) {
        tokenRefreshJob?.cancel()

        val now = Instant.now()
        val ttlMs = java.time.Duration.between(now, expiresAt).toMillis()
        val delayMs = max((ttlMs * TOKEN_REFRESH_RATIO).toLong(), TOKEN_REFRESH_MIN_MS)

        Timber.tag(TAG).d("Token refresh scheduled in ${delayMs / 1000}s (TTL=${ttlMs / 1000}s)")

        tokenRefreshJob = appScope.launch(Dispatchers.IO) {
            delay(delayMs)
            if (isConnected.get()) {
                refreshWsToken()
            }
        }
    }

    /**
     * Exécute le refresh proactif : obtient un nouveau token via l'API REST,
     * puis l'envoie au serveur sur la connexion WebSocket existante.
     *
     * En cas d'échec : log warning sans retry. Le serveur fermera la connexion
     * à l'expiration du token (code 4001) et le mécanisme de reconnexion standard prendra le relais.
     */
    private suspend fun refreshWsToken() {
        val wsTokenInfo = authRepository.getWsToken()
            .onFailure { e ->
                Timber.tag(TAG).w(e, "Proactive WS token refresh failed — server will close at expiry")
            }
            .getOrNull() ?: return

        val refreshPayload = JSONObject().apply {
            put("action", "refresh_token")
            put("token", wsTokenInfo.token)
        }.toString()

        val sent = webSocket?.send(refreshPayload) ?: false
        if (sent) {
            Timber.tag(TAG).d("WS token refresh message sent — scheduling next refresh")
            // Mettre à jour l'expiration pour le check foreground (R2 race condition fix)
            currentTokenExpiresAt = wsTokenInfo.expiresAt
            // Reset backoff — la connexion est saine après un refresh réussi
            reconnectAttempts.set(0)
            scheduleTokenRefresh(wsTokenInfo.expiresAt)
        } else {
            Timber.tag(TAG).w("WS token refresh send failed — connection may be closing")
        }
    }

    // ── WebSocketListener ──────────────────────────────────────────────────────

    private inner class WsListener(
        private val authToken: String,
        private val tokenExpiresAt: Instant,
    ) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Timber.tag(TAG).i("WS onOpen — sending auth token [REDACTED]")
            isConnecting.set(false)
            isConnected.set(true)
            reconnectAttempts.set(0)
            _connectionState.value = WsConnectionState.Connected

            // Premier message : authentification
            val authPayload = JSONObject().apply {
                put("token", authToken)
            }.toString()
            webSocket.send(authPayload)

            // Mémoriser l'expiration pour le check foreground (R2 race condition fix)
            currentTokenExpiresAt = tokenExpiresAt

            // Planifier le refresh proactif du token avant son expiration
            scheduleTokenRefresh(tokenExpiresAt)

            emit(WsEvent.Connected)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(webSocket, text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Timber.tag(TAG).d("WS onClosing code=$code reason=$reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Timber.tag(TAG).i("WS onClosed code=$code reason=$reason")
            tokenRefreshJob?.cancel()
            tokenRefreshJob = null
            currentTokenExpiresAt = null
            isConnected.set(false)
            isConnecting.set(false)
            this@PrivateWsClient.webSocket = null
            _connectionState.value = WsConnectionState.Disconnected
            emit(WsEvent.Disconnected(reason = reason.ifBlank { null }))

            // Reconnexion automatique sauf fermeture normale intentionnelle (1000 depuis disconnect())
            if (code != 1000) {
                scheduleReconnect()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Timber.tag(TAG).w(t, "WS onFailure — ${response?.code}")
            tokenRefreshJob?.cancel()
            tokenRefreshJob = null
            currentTokenExpiresAt = null
            isConnected.set(false)
            isConnecting.set(false)
            this@PrivateWsClient.webSocket = null
            _connectionState.value = WsConnectionState.Disconnected
            emit(WsEvent.Disconnected(reason = t.message))
            scheduleReconnect()
        }
    }

    // ── Parsing des messages ───────────────────────────────────────────────────

    private fun handleMessage(webSocket: WebSocket, text: String) {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            Timber.tag(TAG).w("WS received invalid JSON — ignored")
            return
        }

        val type = json.optString("type", "")
        val data = json.optJSONObject("data") ?: JSONObject()

        when (type) {
            "ping" -> {
                // Répondre au heartbeat applicatif
                val pong = JSONObject().apply { put("type", "pong") }.toString()
                webSocket.send(pong)
            }
            "portfolio_update" -> emit(WsEvent.PortfolioUpdate(data))
            "position_update"  -> emit(WsEvent.PositionUpdate(data))
            "order_update"     -> emit(WsEvent.OrderUpdate(data))
            "notification"     -> {
                val notifType = data.optString("type", "info")
                val title = data.optString("title", "")
                val body  = data.optString("body", data.optString("message", ""))
                emit(WsEvent.Notification(notifType, title, body, data))
            }
            "strategy_signal"  -> emit(WsEvent.StrategySignal(data))
            "catalyst_event"   -> emit(WsEvent.CatalystEvent(data))
            "token_refreshed"  -> Timber.tag(TAG).d("WS token refresh acknowledged by server")
            "error"            -> {
                val code = json.optString("code", "")
                val msg  = json.optString("message", "")
                Timber.tag(TAG).w("WS server error code=%s message=%s", code, msg)
            }
            else -> {
                if (BuildConfig.DEBUG) {
                    Timber.tag(TAG).v("WS unknown message type='$type' — ignored")
                }
            }
        }
    }

    // ── Émission thread-safe ───────────────────────────────────────────────────

    private fun emit(event: WsEvent) {
        // tryEmit() est non-suspending — appelable depuis n'importe quel thread OkHttp.
        // extraBufferCapacity=64 assure qu'on ne perd pas d'événements si le collecteur est lent.
        val emitted = _events.tryEmit(event)
        if (!emitted && BuildConfig.DEBUG) {
            Timber.tag(TAG).w("WS event buffer full — event dropped: ${event::class.simpleName}")
        }
    }
}
