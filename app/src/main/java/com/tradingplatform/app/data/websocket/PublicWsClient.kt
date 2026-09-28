package com.tradingplatform.app.data.websocket

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.tradingplatform.app.BuildConfig
import com.tradingplatform.app.domain.model.WsConnectionState
import kotlinx.coroutines.CancellationException
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
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Client WebSocket public vers `wss://{vps}/ws/public`.
 *
 * ## Protocole
 * Connexion non authentifiée. Après ouverture, envoie un message subscribe
 * pour chaque symbol actuellement référencé dans [symbolRefCounts] :
 * `{"action": "subscribe", "symbols": ["AAPL", "TSLA"]}`.
 *
 * Messages entrants normalisés par le serveur en envelope standard :
 * `{"type": "market_data", "data": {...}, "timestamp": "..."}`.
 *
 * ## Subscriptions ref-comptées (audit #13)
 * Plusieurs collecteurs peuvent suivre le même symbol (ex. Dashboard + MarketData sur le
 * premier symbole de la watchlist). Chaque [subscribe] incrémente un compteur par symbol,
 * chaque [unsubscribe] le décrémente : le frame réseau `subscribe` n'est envoyé qu'à la
 * transition 0→1 et le frame `unsubscribe` qu'à la transition 1→0. Retirer un symbol de
 * la watchlist ne gèle donc plus le cours affiché par le Dashboard.
 *
 * ## État de connexion (audit #14)
 * [connectionState] expose [WsConnectionState] (même contrat que le canal privé) :
 * `Connecting` pendant l'ouverture / l'attente de backoff, `Connected` sur onOpen,
 * `Disconnected` sur onFailure/onClosed/[disconnect] (et sur [onStop] si aucune socket
 * n'est ouverte), `Degraded` à partir de [DEGRADED_RECONNECT_THRESHOLD] tentatives de
 * reconnexion consécutives. Les ViewModels pilotent le fallback REST sur cet état
 * (voir `ui/common/QuoteFallbackController`).
 *
 * ## Ping/Pong applicatif
 * Sur réception d'un `ping`, répond `{"type": "pong"}` (identique au canal privé).
 *
 * ## Reconnexion
 * Backoff exponentiel : 5s → 10s → 20s → … → 300s.
 * Ne se reconnecte que si l'app est en foreground ([isAppForeground]).
 * Au retour de connexion, renvoie les subscriptions actives.
 *
 * ## Thread-safety
 * [symbolRefCounts] est une [ConcurrentHashMap] dont les read-modify-write (compteurs,
 * envoi des frames associés, snapshot de resubscription dans onOpen) sont sérialisés
 * sous [lock]. [_events] est un [MutableSharedFlow] — safe pour émissions depuis
 * n'importe quel thread OkHttp.
 *
 * @param okHttpClient Client principal (cert pinning + VPN check via intercepteurs).
 * @param appScope Scope applicatif (@Singleton) pour les coroutines longue durée.
 * @param baseUrl URL de base du VPS (ex: `https://10.42.0.1:443`).
 */
@Singleton
class PublicWsClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val appScope: CoroutineScope,
    @Named("base_url") private val baseUrl: String,
) : DefaultLifecycleObserver {

    // ── SharedFlow des événements ──────────────────────────────────────────────
    private val _events = MutableSharedFlow<PublicWsEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    val events: SharedFlow<PublicWsEvent> = _events.asSharedFlow()

    // ── État de connexion exposé (audit #14) ──────────────────────────────────
    private val _connectionState = MutableStateFlow(WsConnectionState.Disconnected)

    /** État de la connexion WS publique — pilote le fallback REST des écrans de cours. */
    val connectionState: StateFlow<WsConnectionState> = _connectionState.asStateFlow()

    private val _isAppForeground = MutableStateFlow(false)

    /**
     * True si l'app est visible (foreground). Mis à jour par ProcessLifecycleOwner.
     * Exposé pour que le fallback REST des écrans ne polle pas en arrière-plan.
     */
    val isAppForeground: StateFlow<Boolean> = _isAppForeground.asStateFlow()

    // ── État interne ───────────────────────────────────────────────────────────
    @Volatile private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)

    /**
     * Nombre de collecteurs actifs par symbol (uppercase). Une clé présente = symbol souscrit
     * côté serveur (ou à resouscrire à la prochaine ouverture). Toute mutation se fait sous
     * [lock] pour que la transition 0→1 / 1→0 et l'envoi du frame associé soient atomiques.
     */
    private val symbolRefCounts = ConcurrentHashMap<String, Int>()

    /** Verrou des compteurs de subscription + snapshot de resubscription dans onOpen. */
    private val lock = Any()

    /** Compteur de tentatives consécutives — utilisé pour le backoff exponentiel. */
    private val reconnectAttempts = AtomicInteger(0)

    /** Job du timer de reconnexion en cours — annulé si connect() est rappelé. */
    @Volatile private var reconnectJob: Job? = null

    // URL WebSocket dérivée de baseUrl : https://… → wss://…/ws/public
    private val wsUrl: String
        get() = baseUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
            .trimEnd('/') + "/ws/public"

    companion object {
        private const val TAG = "PublicWsClient"

        /**
         * Nombre de tentatives de reconnexion consécutives à partir duquel l'état exposé
         * passe à [WsConnectionState.Degraded] au lieu de [WsConnectionState.Connecting].
         */
        internal const val DEGRADED_RECONNECT_THRESHOLD = 3

        /**
         * Parse un message `market_data` en [PublicWsEvent.MarketData].
         * Retourne null si le symbol est absent ou si le parsing du prix échoue.
         *
         * Champs fournis par MarketDataBridge (cf. TP2 market_data_bridge.py §_forward) :
         * symbol, price, open, high, low, close, volume, bid (nullable), ask (nullable).
         * Le timestamp est au niveau racine de l'enveloppe serveur.
         *
         * `internal` (et non `private`) pour être exercée directement par
         * `PublicWsClientParseTest` sans construire un [PublicWsClient] (dont le `init`
         * bloc a des dépendances Android).
         */
        internal fun parseMarketData(data: JSONObject, timestampStr: String): PublicWsEvent.MarketData? {
            val symbol = data.optString("symbol", "").uppercase()
            if (symbol.isEmpty()) {
                Timber.tag(TAG).w("market_data message missing symbol — ignored")
                return null
            }

            return try {
                val price = data.optString("price", "0").let { BigDecimal(it) }
                val open = data.optString("open", "0").let { BigDecimal(it) }
                val high = data.optString("high", "0").let { BigDecimal(it) }
                val low = data.optString("low", "0").let { BigDecimal(it) }
                val close = data.optString("close", "0").let { BigDecimal(it) }
                // Audit finding #E (plan-market-data.md §E) — volume can be serialized as a
                // decimal string (e.g. "123456.0") by MarketDataBridge; toLongOrNull() would
                // return null for that shape and silently default to 0. Parse as BigDecimal
                // first, then truncate to Long.
                val volume = data.optString("volume", "").toBigDecimalOrNull()?.toLong() ?: 0L
                // bid/ask sont nullable côté serveur (champs optionnels du Redis Stream)
                val bid = if (data.isNull("bid")) null else data.optString("bid", "").let {
                    if (it.isNotEmpty()) BigDecimal(it) else null
                }
                val ask = if (data.isNull("ask")) null else data.optString("ask", "").let {
                    if (it.isNotEmpty()) BigDecimal(it) else null
                }
                val timestamp = if (timestampStr.isNotEmpty()) {
                    Instant.parse(timestampStr)
                } else {
                    Instant.now()
                }

                val sourceName = data.optString("source_name", "").ifEmpty { null }
                val sourceType = data.optString("source_type", "").ifEmpty { null }
                val quality = if (data.has("quality") && !data.isNull("quality")) {
                    data.optInt("quality", -1).takeIf { it >= 0 }
                } else null
                val dataMode = data.optString("data_mode", "").ifEmpty { null }

                PublicWsEvent.MarketData(
                    symbol = symbol,
                    price = price,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = volume,
                    bid = bid,
                    ask = ask,
                    timestamp = timestamp,
                    sourceName = sourceName,
                    sourceType = sourceType,
                    quality = quality,
                    dataMode = dataMode,
                )
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to parse market_data for symbol=$symbol — ignored")
                null
            }
        }
    }

    // ── Lifecycle app ──────────────────────────────────────────────────────────

    init {
        // Enregistrer l'observateur sur le thread principal (ProcessLifecycleOwner l'exige).
        // Dispatchers.Main.immediate : si on est déjà sur Main, exécution synchrone immédiate
        // (pas de dispatch supplémentaire), sinon dispatch normal. Non-bloquant dans tous les cas.
        appScope.launch(Dispatchers.Main.immediate) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this@PublicWsClient)
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        _isAppForeground.value = true
        // L'app revient en foreground : connecter si des symbols sont actifs et pas encore connecté
        if (symbolRefCounts.isNotEmpty() && !isConnected.get() && !isConnecting.get()) {
            connect()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        _isAppForeground.value = false
        // App en background : annuler le timer de reconnexion pour économiser la batterie.
        // La connexion existante reste ouverte — fermée par le serveur (idle timeout 300s).
        reconnectJob?.cancel()
        reconnectJob = null
        // Plus aucune reconnexion planifiée : si aucune socket n'est ouverte ni en cours
        // d'ouverture, l'état Connecting/Degraded exposé n'est plus vrai → Disconnected.
        // Une socket encore ouverte reste Connected (elle continue de livrer des cours).
        if (!isConnected.get() && !isConnecting.get()) {
            _connectionState.value = WsConnectionState.Disconnected
        }
        Timber.tag(TAG).d("App went to background — reconnect timer cancelled")
    }

    // ── API publique ───────────────────────────────────────────────────────────

    /**
     * Ajoute un collecteur pour [symbol] (ref-count). Le frame `subscribe` n'est envoyé
     * qu'à la transition 0→1 et seulement si la socket est ouverte — sinon la connexion
     * est créée et onOpen resouscrit toutes les clés de [symbolRefCounts].
     */
    fun subscribe(symbol: String) {
        val upper = symbol.uppercase()
        val needsConnect = synchronized(lock) {
            val previous = symbolRefCounts[upper] ?: 0
            symbolRefCounts[upper] = previous + 1
            if (isConnected.get()) {
                if (previous == 0) sendSubscribe(listOf(upper))
                false
            } else {
                !isConnecting.get()
            }
        }
        if (needsConnect) connect()
    }

    /**
     * Retire un collecteur de [symbol] (ref-count). Le frame `unsubscribe` n'est envoyé
     * qu'à la transition 1→0 ; si plus aucun symbol n'est référencé, la connexion est
     * fermée (et tout timer de reconnexion annulé). Un unsubscribe sans subscribe
     * correspondant est ignoré.
     */
    fun unsubscribe(symbol: String) {
        val upper = symbol.uppercase()
        val shouldDisconnect = synchronized(lock) {
            val previous = symbolRefCounts[upper] ?: return
            if (previous > 1) {
                symbolRefCounts[upper] = previous - 1
                return
            }
            symbolRefCounts.remove(upper)
            if (isConnected.get()) sendUnsubscribe(listOf(upper))
            symbolRefCounts.isEmpty()
        }
        if (shouldDisconnect) disconnect()
    }

    /**
     * Ferme la connexion proprement (code 1000 Normal Closure).
     * N'efface pas [symbolRefCounts] — une reconnexion future rétablira les subscriptions.
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
        _connectionState.value = WsConnectionState.Disconnected
    }

    // ── Connexion interne ──────────────────────────────────────────────────────

    private fun connect() {
        if (isConnected.get() || isConnecting.get()) {
            Timber.tag(TAG).d("connect() — already connected or connecting, skipping")
            return
        }
        appScope.launch(Dispatchers.IO) { openWebSocket() }
    }

    private fun openWebSocket() {
        if (!isConnecting.compareAndSet(false, true)) return
        _connectionState.value = pendingState()

        try {
            val request = Request.Builder()
                .url(wsUrl)
                .build()

            Timber.tag(TAG).d("Opening public WS connection to $wsUrl")
            webSocket = okHttpClient.newWebSocket(request, WsListener())
            // isConnecting reste true jusqu'à onOpen ou onFailure
        } catch (e: Exception) {
            // Ne jamais avaler une annulation de coroutine (concurrence structurée).
            if (e is CancellationException) throw e
            // newWebSocket() peut échouer immédiatement (URL invalide, client shutdown…).
            // Sans ce catch, isConnecting resterait true → plus aucune reconnexion possible.
            Timber.tag(TAG).w(e, "openWebSocket() failed immediately — resetting isConnecting")
            isConnecting.set(false)
            _connectionState.value = WsConnectionState.Disconnected
            scheduleReconnect()
        }
    }

    /**
     * État à exposer pendant une (re)connexion : [WsConnectionState.Degraded] à partir de
     * [DEGRADED_RECONNECT_THRESHOLD] tentatives consécutives, sinon [WsConnectionState.Connecting].
     */
    private fun pendingState(): WsConnectionState =
        if (reconnectAttempts.get() >= DEGRADED_RECONNECT_THRESHOLD) {
            WsConnectionState.Degraded
        } else {
            WsConnectionState.Connecting
        }

    // ── Envoi de messages ──────────────────────────────────────────────────────

    private fun sendSubscribe(symbols: List<String>) {
        if (symbols.isEmpty()) return
        val msg = JSONObject().apply {
            put("action", "subscribe")
            put("symbols", JSONArray(symbols))
        }.toString()
        webSocket?.send(msg)
        Timber.tag(TAG).d("Sent subscribe: $symbols")
    }

    private fun sendUnsubscribe(symbols: List<String>) {
        if (symbols.isEmpty()) return
        val msg = JSONObject().apply {
            put("action", "unsubscribe")
            put("symbols", JSONArray(symbols))
        }.toString()
        webSocket?.send(msg)
        Timber.tag(TAG).d("Sent unsubscribe: $symbols")
    }

    // ── Reconnexion avec backoff ───────────────────────────────────────────────

    private fun scheduleReconnect() {
        if (!_isAppForeground.value || symbolRefCounts.isEmpty()) {
            Timber.tag(TAG).d("scheduleReconnect() — skipped (background or no active symbols)")
            return
        }

        reconnectJob?.cancel()
        val attempts = reconnectAttempts.getAndIncrement()
        val delayMs = WsBackoff.computeDelayMs(attempts)

        Timber.tag(TAG).d("Scheduling public WS reconnect in ${delayMs}ms (attempt #${attempts + 1})")
        // Connecting (ou Degraded après N échecs) pendant l'attente du backoff — le debounce
        // du QuoteFallbackController évite de basculer en polling REST sur un simple flap.
        _connectionState.value = pendingState()
        reconnectJob = appScope.launch(Dispatchers.IO) {
            delay(delayMs)
            if (_isAppForeground.value && symbolRefCounts.isNotEmpty() &&
                !isConnected.get() && !isConnecting.get()
            ) {
                openWebSocket()
            }
        }
    }

    // ── WebSocketListener ──────────────────────────────────────────────────────

    private inner class WsListener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Timber.tag(TAG).i("Public WS onOpen")
            reconnectAttempts.set(0)

            // Renvoyer toutes les subscriptions actives après (re)connexion. Sous [lock] :
            // un subscribe() concurrent voit soit isConnected=false (sa clé est alors dans le
            // snapshot), soit isConnected=true après l'envoi du snapshot (il envoie son frame).
            synchronized(lock) {
                isConnecting.set(false)
                isConnected.set(true)
                val symbols = symbolRefCounts.keys.toList()
                if (symbols.isNotEmpty()) {
                    sendSubscribe(symbols)
                }
            }

            _connectionState.value = WsConnectionState.Connected
            emit(PublicWsEvent.Connected)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(webSocket, text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Timber.tag(TAG).d("Public WS onClosing code=$code reason=$reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Timber.tag(TAG).i("Public WS onClosed code=$code reason=$reason")
            isConnected.set(false)
            isConnecting.set(false)
            this@PublicWsClient.webSocket = null
            _connectionState.value = WsConnectionState.Disconnected
            emit(PublicWsEvent.Disconnected(reason = reason.ifBlank { null }))

            // Reconnexion automatique sauf fermeture normale intentionnelle (1000 depuis disconnect())
            if (code != 1000) {
                scheduleReconnect()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Timber.tag(TAG).w(t, "Public WS onFailure — ${response?.code}")
            isConnected.set(false)
            isConnecting.set(false)
            this@PublicWsClient.webSocket = null
            _connectionState.value = WsConnectionState.Disconnected
            emit(PublicWsEvent.Disconnected(reason = t.message))
            scheduleReconnect()
        }
    }

    // ── Parsing des messages ───────────────────────────────────────────────────

    private fun handleMessage(webSocket: WebSocket, text: String) {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            Timber.tag(TAG).w("Public WS received invalid JSON — ignored")
            return
        }

        val type = json.optString("type", "")
        val data = json.optJSONObject("data") ?: JSONObject()
        // Le timestamp est au niveau racine de l'enveloppe (cf. _ensure_envelope côté serveur)
        val timestampStr = json.optString("timestamp", "")

        when (type) {
            "ping" -> {
                // Heartbeat applicatif — répondre immédiatement
                val pong = JSONObject().apply { put("type", "pong") }.toString()
                webSocket.send(pong)
            }
            "market_data" -> {
                val event = parseMarketData(data, timestampStr)
                if (event != null) emit(event)
            }
            "subscription_ack" -> {
                // Confirmation de subscribe/unsubscribe — log en debug uniquement
                if (BuildConfig.DEBUG) {
                    val action = data.optString("action", "")
                    val symbols = data.optJSONArray("symbols")
                    Timber.tag(TAG).d("subscription_ack action=$action symbols=$symbols")
                }
            }
            "error" -> {
                val code = json.optString("code", "")
                val msg = json.optString("message", "")
                Timber.tag(TAG).w("Public WS server error code=$code message=$msg")
            }
            else -> {
                if (BuildConfig.DEBUG) {
                    Timber.tag(TAG).v("Public WS unknown message type='$type' — ignored")
                }
            }
        }
    }

    // ── Émission thread-safe ───────────────────────────────────────────────────

    private fun emit(event: PublicWsEvent) {
        // tryEmit() est non-suspending — appelable depuis n'importe quel thread OkHttp.
        // extraBufferCapacity=64 assure qu'on ne perd pas d'événements si le collecteur est lent.
        val emitted = _events.tryEmit(event)
        if (!emitted && BuildConfig.DEBUG) {
            Timber.tag(TAG).w("Public WS event buffer full — event dropped: ${event::class.simpleName}")
        }
    }
}
