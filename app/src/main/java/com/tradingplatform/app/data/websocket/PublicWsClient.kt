package com.tradingplatform.app.data.websocket

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.tradingplatform.app.BuildConfig
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
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.math.pow

/**
 * Client WebSocket public vers `wss://{vps}/ws/public`.
 *
 * ## Protocole
 * Connexion non authentifiée. Après ouverture, envoie un message subscribe
 * pour chaque symbol actuellement dans [activeSymbols] :
 * `{"action": "subscribe", "symbols": ["AAPL", "TSLA"]}`.
 *
 * Messages entrants normalisés par le serveur en envelope standard :
 * `{"type": "market_data", "data": {...}, "timestamp": "..."}`.
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
 * [activeSymbols] est un [CopyOnWriteArraySet] — safe pour accès concurrent.
 * [_events] est un [MutableSharedFlow] — safe pour émissions depuis n'importe quel thread OkHttp.
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

    // ── État interne ───────────────────────────────────────────────────────────
    @Volatile private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)

    /** Symbols actuellement demandés — persistés pour resubscription après reconnexion. */
    private val activeSymbols = CopyOnWriteArraySet<String>()

    /** Compteur de tentatives consécutives — utilisé pour le backoff exponentiel. */
    private val reconnectAttempts = AtomicInteger(0)

    /** Job du timer de reconnexion en cours — annulé si connect() est rappelé. */
    @Volatile private var reconnectJob: Job? = null

    /** True si l'app est visible (foreground). Mis à jour par ProcessLifecycleOwner. */
    @Volatile private var isAppForeground = false

    // URL WebSocket dérivée de baseUrl : https://… → wss://…/ws/public
    private val wsUrl: String
        get() = baseUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
            .trimEnd('/') + "/ws/public"

    companion object {
        private const val TAG = "PublicWsClient"
        private const val BACKOFF_INITIAL_MS = 5_000L
        private const val BACKOFF_MAX_MS = 300_000L
        private const val BACKOFF_MULTIPLIER = 2.0
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
        isAppForeground = true
        // L'app revient en foreground : connecter si des symbols sont actifs et pas encore connecté
        if (activeSymbols.isNotEmpty() && !isConnected.get() && !isConnecting.get()) {
            connect()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        isAppForeground = false
        // App en background : annuler le timer de reconnexion pour économiser la batterie.
        // La connexion existante reste ouverte — fermée par le serveur (idle timeout 300s).
        reconnectJob?.cancel()
        reconnectJob = null
        Timber.tag(TAG).d("App went to background — reconnect timer cancelled")
    }

    // ── API publique ───────────────────────────────────────────────────────────

    /**
     * S'abonne à un symbol. Si la connexion n'est pas encore établie, elle est créée.
     * Si déjà connecté, envoie immédiatement le message subscribe.
     */
    fun subscribe(symbol: String) {
        val upper = symbol.uppercase()
        activeSymbols.add(upper)
        if (isConnected.get()) {
            sendSubscribe(listOf(upper))
        } else if (!isConnecting.get()) {
            connect()
        }
    }

    /**
     * Se désabonne d'un symbol. Si plus aucun symbol n'est actif, ferme la connexion.
     */
    fun unsubscribe(symbol: String) {
        val upper = symbol.uppercase()
        activeSymbols.remove(upper)
        if (isConnected.get()) {
            sendUnsubscribe(listOf(upper))
            if (activeSymbols.isEmpty()) {
                disconnect()
            }
        }
    }

    /**
     * Ferme la connexion proprement (code 1000 Normal Closure).
     * N'efface pas [activeSymbols] — une reconnexion future rétablira les subscriptions.
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

    private fun connect() {
        if (isConnected.get() || isConnecting.get()) {
            Timber.tag(TAG).d("connect() — already connected or connecting, skipping")
            return
        }
        appScope.launch(Dispatchers.IO) { openWebSocket() }
    }

    private fun openWebSocket() {
        if (!isConnecting.compareAndSet(false, true)) return

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        Timber.tag(TAG).d("Opening public WS connection to $wsUrl")
        webSocket = okHttpClient.newWebSocket(request, WsListener())
        // isConnecting reste true jusqu'à onOpen ou onFailure
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
        if (!isAppForeground || activeSymbols.isEmpty()) {
            Timber.tag(TAG).d("scheduleReconnect() — skipped (background or no active symbols)")
            return
        }

        reconnectJob?.cancel()
        val attempts = reconnectAttempts.getAndIncrement()
        val delayMs = minOf(
            (BACKOFF_INITIAL_MS * BACKOFF_MULTIPLIER.pow(attempts)).toLong(),
            BACKOFF_MAX_MS,
        )

        Timber.tag(TAG).d("Scheduling public WS reconnect in ${delayMs}ms (attempt #${attempts + 1})")
        reconnectJob = appScope.launch(Dispatchers.IO) {
            delay(delayMs)
            if (isAppForeground && activeSymbols.isNotEmpty() && !isConnected.get() && !isConnecting.get()) {
                openWebSocket()
            }
        }
    }

    // ── WebSocketListener ──────────────────────────────────────────────────────

    private inner class WsListener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Timber.tag(TAG).i("Public WS onOpen")
            isConnecting.set(false)
            isConnected.set(true)
            reconnectAttempts.set(0)

            // Renvoyer toutes les subscriptions actives après reconnexion
            val symbols = activeSymbols.toList()
            if (symbols.isNotEmpty()) {
                sendSubscribe(symbols)
            }

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

    /**
     * Parse un message `market_data` en [PublicWsEvent.MarketData].
     * Retourne null si le symbol est absent ou si le parsing du prix échoue.
     *
     * Champs fournis par MarketDataBridge (cf. TP2 market_data_bridge.py §_forward) :
     * symbol, price, open, high, low, close, volume, bid (nullable), ask (nullable).
     * Le timestamp est au niveau racine de l'enveloppe serveur.
     */
    private fun parseMarketData(data: JSONObject, timestampStr: String): PublicWsEvent.MarketData? {
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
            val volume = data.optString("volume", "0").toLongOrNull() ?: 0L
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
