package com.tradingplatform.app.data.websocket

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.tradingplatform.app.BuildConfig
import com.tradingplatform.app.domain.repository.AuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
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

    // ── État interne ───────────────────────────────────────────────────────────
    @Volatile private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)

    /** Compteur de tentatives consécutives — utilisé pour le backoff exponentiel. */
    private val reconnectAttempts = AtomicInteger(0)

    /** Job du timer de reconnexion en cours — annulé si connect() est rappelé. */
    private var reconnectJob: Job? = null

    /** True si l'app est visible (foreground). Mis à jour par ProcessLifecycleOwner. */
    @Volatile private var isAppForeground = false

    // URL WebSocket dérivée de baseUrl : https://… → wss://…/v1/ws/private
    private val wsUrl: String
        get() = baseUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
            .trimEnd('/') + "/v1/ws/private"

    companion object {
        private const val TAG = "PrivateWsClient"
        private const val BACKOFF_INITIAL_MS = 5_000L
        private const val BACKOFF_MAX_MS = 300_000L
        private const val BACKOFF_MULTIPLIER = 2.0
    }

    // ── Lifecycle app ──────────────────────────────────────────────────────────

    init {
        // Enregistrer l'observateur sur le thread principal (ProcessLifecycleOwner l'exige)
        appScope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this@PrivateWsClient)
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        isAppForeground = true
        // L'app revient en foreground : connecter si pas déjà connecté
        if (!isConnected.get() && !isConnecting.get()) {
            connect()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        isAppForeground = false
        // App en background : annuler le timer de reconnexion pour économiser la batterie.
        // La connexion existante reste ouverte — elle sera fermée par le serveur (idle timeout).
        reconnectJob?.cancel()
        reconnectJob = null
        Timber.tag(TAG).d("App went to background — reconnect timer cancelled")
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
        appScope.launch(Dispatchers.IO) { openWebSocket() }
    }

    /**
     * Ferme la connexion proprement (code 1000 Normal Closure).
     * Annule le timer de reconnexion — ne se reconnecte pas automatiquement.
     */
    fun disconnect() {
        Timber.tag(TAG).d("disconnect() — closing WebSocket gracefully")
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts.set(0)
        webSocket?.close(1000, "Client disconnecting")
        webSocket = null
        isConnected.set(false)
        isConnecting.set(false)
    }

    // ── Connexion interne ──────────────────────────────────────────────────────

    private suspend fun openWebSocket() {
        if (!isConnecting.compareAndSet(false, true)) return

        // Obtenir le token WS (POST /v1/auth/ws-token)
        val token = authRepository.getWsToken()
            .onFailure { e ->
                Timber.tag(TAG).w(e, "Failed to fetch WS token — will retry")
                isConnecting.set(false)
                scheduleReconnect()
            }
            .getOrNull() ?: return

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        Timber.tag(TAG).d("Opening WS connection to $wsUrl")
        webSocket = okHttpClient.newWebSocket(request, WsListener(token))
        // isConnecting reste true jusqu'à onOpen ou onFailure
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
        reconnectJob = appScope.launch(Dispatchers.IO) {
            delay(delayMs)
            if (isAppForeground && !isConnected.get() && !isConnecting.get()) {
                openWebSocket()
            }
        }
    }

    // ── WebSocketListener ──────────────────────────────────────────────────────

    private inner class WsListener(private val authToken: String) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Timber.tag(TAG).i("WS onOpen — sending auth token [REDACTED]")
            isConnecting.set(false)
            isConnected.set(true)
            reconnectAttempts.set(0)

            // Premier message : authentification
            val authPayload = JSONObject().apply {
                put("token", authToken)
            }.toString()
            webSocket.send(authPayload)

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
            isConnected.set(false)
            isConnecting.set(false)
            this@PrivateWsClient.webSocket = null
            emit(WsEvent.Disconnected(reason = reason.ifBlank { null }))

            // Reconnexion automatique sauf fermeture normale intentionnelle (1000 depuis disconnect())
            if (code != 1000) {
                scheduleReconnect()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Timber.tag(TAG).w(t, "WS onFailure — ${response?.code}")
            isConnected.set(false)
            isConnecting.set(false)
            this@PrivateWsClient.webSocket = null
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
                Timber.tag(TAG).w("WS server error code=$code message=$msg")
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
