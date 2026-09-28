package com.tradingplatform.app.data.websocket

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.tradingplatform.app.domain.model.WsConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * PR 3.4 (audit #13/#14) — ref-counted subscriptions and exposed connection state of
 * [PublicWsClient], against a real WebSocket served by [MockWebServer].
 *
 * Robolectric is used only because the client's `init` registers itself on
 * [ProcessLifecycleOwner] from `Dispatchers.Main` (Android main looper). The foreground
 * transition is driven explicitly via [PublicWsClient.onStart].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PublicWsClientTest {

    private lateinit var server: MockWebServer
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var appScope: CoroutineScope
    private lateinit var client: PublicWsClient

    /** Text frames received by the server (client → server). */
    private val serverFrames = LinkedBlockingQueue<String>()

    /** Close codes received by the server (client-initiated close). */
    private val serverCloses = LinkedBlockingQueue<Int>()

    /** Server-side socket of the current connection. */
    private val serverSockets = LinkedBlockingQueue<WebSocket>()

    private val serverListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            serverSockets.put(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            serverFrames.put(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            serverCloses.put(code)
            webSocket.close(1000, null)
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        // Several upgrades available (reconnection test).
        repeat(3) { server.enqueue(MockResponse().withWebSocketUpgrade(serverListener)) }
        server.start()

        okHttpClient = OkHttpClient()
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        client = PublicWsClient(
            okHttpClient = okHttpClient,
            appScope = appScope,
            baseUrl = server.url("/").toString(),
        )
        // Foreground — required for reconnection scheduling.
        client.onStart(ProcessLifecycleOwner.get())
    }

    @After
    fun tearDown() {
        client.disconnect()
        appScope.cancel()
        okHttpClient.dispatcher.executorService.shutdown()
        server.shutdown()
    }

    private fun awaitState(expected: WsConnectionState) = runBlocking {
        withTimeout(5_000L) { client.connectionState.first { it == expected } }
    }

    private fun nextFrame(): JSONObject {
        val raw = serverFrames.poll(5, TimeUnit.SECONDS)
            ?: throw AssertionError("Expected a client frame, got none")
        return JSONObject(raw)
    }

    private fun JSONObject.symbols(): List<String> {
        val array = getJSONArray("symbols")
        return (0 until array.length()).map { array.getString(it) }
    }

    @Test
    fun `second subscriber and first unsubscribe send no frame, last unsubscribe sends frame and closes`() {
        assertEquals(WsConnectionState.Disconnected, client.connectionState.value)

        client.subscribe("AAPL")
        awaitState(WsConnectionState.Connected)

        // onOpen resubscribes every referenced symbol
        val subscribe = nextFrame()
        assertEquals("subscribe", subscribe.getString("action"))
        assertEquals(listOf("AAPL"), subscribe.symbols())

        // Second collector (case-insensitive) — 1 → 2, no frame
        client.subscribe("aapl")
        // One collector leaves — 2 → 1, no frame, connection kept
        client.unsubscribe("AAPL")
        assertNull(
            "No frame expected while another collector still holds AAPL",
            serverFrames.poll(500, TimeUnit.MILLISECONDS),
        )
        assertEquals(WsConnectionState.Connected, client.connectionState.value)
        assertNull(serverCloses.poll(0, TimeUnit.MILLISECONDS))

        // Last collector leaves — 1 → 0: unsubscribe frame, then graceful close
        client.unsubscribe("AAPL")
        val unsubscribe = nextFrame()
        assertEquals("unsubscribe", unsubscribe.getString("action"))
        assertEquals(listOf("AAPL"), unsubscribe.symbols())
        assertEquals(1000, serverCloses.poll(5, TimeUnit.SECONDS))
        assertEquals(WsConnectionState.Disconnected, client.connectionState.value)
    }

    @Test
    fun `new symbol while connected sends a single subscribe frame`() {
        client.subscribe("AAPL")
        awaitState(WsConnectionState.Connected)
        assertEquals(listOf("AAPL"), nextFrame().symbols())

        client.subscribe("TSLA")
        val frame = nextFrame()
        assertEquals("subscribe", frame.getString("action"))
        assertEquals(listOf("TSLA"), frame.symbols())

        // Unsubscribing TSLA keeps AAPL — unsubscribe frame but no close
        client.unsubscribe("TSLA")
        val unsubscribe = nextFrame()
        assertEquals("unsubscribe", unsubscribe.getString("action"))
        assertEquals(listOf("TSLA"), unsubscribe.symbols())
        assertNull(serverCloses.poll(300, TimeUnit.MILLISECONDS))
        assertEquals(WsConnectionState.Connected, client.connectionState.value)
    }

    @Test
    fun `unbalanced unsubscribe is ignored`() {
        client.subscribe("AAPL")
        awaitState(WsConnectionState.Connected)
        nextFrame()

        client.unsubscribe("MSFT")
        assertNull(serverFrames.poll(300, TimeUnit.MILLISECONDS))
        assertEquals(WsConnectionState.Connected, client.connectionState.value)
    }

    @Test
    fun `server-side close moves state to Disconnected then Connecting and resubscribes on reconnect`() {
        client.subscribe("AAPL")
        awaitState(WsConnectionState.Connected)
        assertEquals(listOf("AAPL"), nextFrame().symbols())

        // Abnormal server close (1011) → onClosed(code != 1000) → reconnect scheduled
        val serverSocket = serverSockets.poll(5, TimeUnit.SECONDS)!!
        serverSocket.close(1011, "server restart")

        // Disconnected is set then immediately replaced by Connecting (backoff pending)
        awaitState(WsConnectionState.Connecting)

        // First backoff = 5 s (WsBackoff.INITIAL_MS) — reconnect + resubscription of all keys
        awaitStateWithin(WsConnectionState.Connected, timeoutMs = 10_000L)
        val resubscribe = serverFrames.poll(5, TimeUnit.SECONDS)
            ?.let(::JSONObject)
            ?: throw AssertionError("Expected resubscribe frame after reconnection")
        assertEquals("subscribe", resubscribe.getString("action"))
        assertEquals(listOf("AAPL"), resubscribe.symbols())
    }

    @Test
    fun `disconnect exposes Disconnected`() {
        client.subscribe("AAPL")
        awaitState(WsConnectionState.Connected)

        client.disconnect()
        assertEquals(WsConnectionState.Disconnected, client.connectionState.value)
    }

    private fun awaitStateWithin(expected: WsConnectionState, timeoutMs: Long) = runBlocking {
        withTimeout(timeoutMs) { client.connectionState.first { it == expected } }
    }
}
