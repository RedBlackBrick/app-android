package com.tradingplatform.app.data.websocket

import android.app.Application
import androidx.lifecycle.LifecycleOwner
import com.tradingplatform.app.data.session.SessionManager
import com.tradingplatform.app.data.session.TokenHolder
import com.tradingplatform.app.domain.model.WsConnectionState
import com.tradingplatform.app.domain.model.WsTokenInfo
import com.tradingplatform.app.domain.repository.AuthRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cycle de vie du WS privé piloté par la session (audit #12, plan-auth-network §C).
 *
 * Robolectric : l'init de [PrivateWsClient] enregistre l'observer sur `ProcessLifecycleOwner`
 * via `Dispatchers.Main.immediate` (Looper Android requis). Le foreground est simulé en appelant
 * [PrivateWsClient.onStart] directement.
 *
 * Le scope applicatif est `UnconfinedTestDispatcher` : les collecteurs des événements de session
 * s'exécutent en ligne dans le thread qui émet. Les connexions (Dispatchers.IO) et le socket
 * (MockWebServer `withWebSocketUpgrade`) tournent sur de vrais threads — d'où [awaitUntil].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PrivateWsClientTest {

    private val server = MockWebServer()
    private val okHttpClient = OkHttpClient()
    private val authRepository = mockk<AuthRepository>()
    private val tokenHolder = TokenHolder()
    private val sessionStarted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val forcedLogout = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val sessionManager = mockk<SessionManager> {
        every { sessionStartedEvents } returns sessionStarted.asSharedFlow()
        every { forcedLogoutEvents } returns forcedLogout.asSharedFlow()
    }
    private val appScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
    private val owner = mockk<LifecycleOwner>(relaxed = true)
    private val serverSide = ServerSide()

    private lateinit var client: PrivateWsClient

    private val wsToken = WsTokenInfo(token = "ws-jwt", expiresAt = Instant.now().plusSeconds(600))

    @Before
    fun setUp() {
        server.start()
        coEvery { authRepository.getWsToken() } returns Result.success(wsToken)
    }

    @After
    fun tearDown() {
        if (::client.isInitialized) client.disconnect()
        appScope.cancel()
        okHttpClient.dispatcher.executorService.shutdownNow()
        server.shutdown()
    }

    private fun newClient(): PrivateWsClient = PrivateWsClient(
        okHttpClient = okHttpClient,
        authRepository = authRepository,
        tokenHolder = tokenHolder,
        sessionManager = sessionManager,
        appScope = appScope,
        baseUrl = server.url("/").toString(),
    ).also { client = it }

    private fun upgrade(): MockResponse = MockResponse().withWebSocketUpgrade(serverSide)

    private fun connectAndAwaitOpen() {
        client.onStart(owner) // foreground + connect()
        awaitUntil { client.connectionState.value == WsConnectionState.Connected }
        assertTrue("server never saw the auth message", serverSide.authReceived.await(5, TimeUnit.SECONDS))
    }

    // ── En-tête Origin (contrôle d'origine du backend, manager.py:_check_origin) ──

    @Test
    fun `handshake carries the Origin header the backend whitelist compares against`() {
        tokenHolder.setToken("access")
        server.enqueue(upgrade())
        newClient()
        connectAndAwaitOpen()

        val handshake = server.takeRequest(5, TimeUnit.SECONDS)
            ?: throw AssertionError("The server never saw the upgrade request")
        assertEquals("http://${server.hostName}:${server.port}", handshake.getHeader("Origin"))
    }

    // ── Fin de session ─────────────────────────────────────────────────────────

    @Test
    fun `forced logout closes the socket with 1000, goes Disconnected and does not reconnect`() {
        tokenHolder.setToken("access")
        server.enqueue(upgrade())
        newClient()
        connectAndAwaitOpen()

        forcedLogout.tryEmit(Unit)

        assertEquals(WsConnectionState.Disconnected, client.connectionState.value)
        assertTrue("server never saw a close frame", serverSide.closing.await(5, TimeUnit.SECONDS))
        assertEquals(1000, serverSide.closeCode.get())
        // Laisser le temps à onClosed (génération périmée) de passer : aucune reconnexion.
        Thread.sleep(300)
        assertEquals(WsConnectionState.Disconnected, client.connectionState.value)
        assertFalse(client.hasPendingReconnectForTest)
        assertEquals(1, server.requestCount)
        coVerify(exactly = 1) { authRepository.getWsToken() }
    }

    @Test
    fun `server close after the token was cleared stops the reconnect loop`() {
        tokenHolder.setToken("access")
        server.enqueue(upgrade())
        newClient()
        connectAndAwaitOpen()

        // Logout en cours : holder vidé, événement pas encore reçu — le serveur coupe (1011,
        // non terminal) : scheduleReconnect() ne doit rien planifier.
        tokenHolder.clear()
        serverSide.socket!!.close(1011, "server restart")

        awaitUntil { client.connectionState.value == WsConnectionState.Disconnected }
        Thread.sleep(300)
        assertFalse(client.hasPendingReconnectForTest)
        assertEquals(WsConnectionState.Disconnected, client.connectionState.value)
    }

    @Test
    fun `server close with an active session schedules a reconnect`() {
        // Témoin du test précédent : même scénario, token présent → reconnexion planifiée.
        tokenHolder.setToken("access")
        server.enqueue(upgrade())
        newClient()
        connectAndAwaitOpen()

        serverSide.socket!!.close(1011, "server restart")

        awaitUntil { client.hasPendingReconnectForTest }
        assertEquals(1, client.reconnectAttemptsForTest)
    }

    // ── Garde TokenHolder ──────────────────────────────────────────────────────

    @Test
    fun `connect without an access token is a no-op`() {
        newClient()

        client.onStart(owner)
        client.connect()

        assertEquals(WsConnectionState.Disconnected, client.connectionState.value)
        Thread.sleep(300)
        coVerify(exactly = 0) { authRepository.getWsToken() }
        assertEquals(0, server.requestCount)
        assertFalse(client.hasPendingReconnectForTest)
    }

    // ── Début de session ───────────────────────────────────────────────────────

    @Test
    fun `session started after five failed attempts resets the backoff and connects immediately`() {
        tokenHolder.setToken("access")
        val failure = Result.failure<WsTokenInfo>(IOException("ws-token unavailable"))
        coEvery { authRepository.getWsToken() } returnsMany
            listOf(failure, failure, failure, failure, failure, Result.success(wsToken))
        server.enqueue(upgrade())
        newClient()

        client.onStart(owner) // tentative 1 → échec → backoff
        awaitUntil { client.reconnectAttemptsForTest == 1 }
        for (expected in 2..5) {
            client.connect()
            awaitUntil { client.reconnectAttemptsForTest == expected }
        }
        assertTrue(client.hasPendingReconnectForTest) // prochain palier : 80 s

        sessionStarted.tryEmit(Unit)

        // Bien en deçà du palier de backoff (80 s) : la connexion est immédiate.
        awaitUntil(timeoutMs = 5_000) { client.connectionState.value == WsConnectionState.Connected }
        assertEquals(0, client.reconnectAttemptsForTest)
        assertFalse(client.hasPendingReconnectForTest)
        coVerify(exactly = 6) { authRepository.getWsToken() }
    }

    // ── Génération ─────────────────────────────────────────────────────────────

    @Test
    fun `logout during the ws-token fetch does not open a socket`() {
        tokenHolder.setToken("access")
        val gate = CompletableDeferred<Unit>()
        coEvery { authRepository.getWsToken() } coAnswers {
            gate.await()
            Result.success(wsToken)
        }
        server.enqueue(upgrade())
        newClient()

        client.onStart(owner)
        coVerify(timeout = 5_000) { authRepository.getWsToken() }

        forcedLogout.tryEmit(Unit)
        gate.complete(Unit)

        Thread.sleep(500)
        assertEquals(0, server.requestCount)
        assertEquals(WsConnectionState.Disconnected, client.connectionState.value)
        assertFalse(client.hasPendingReconnectForTest)
    }

    @Test
    fun `handshake completing after disconnect is closed without authenticating`() {
        tokenHolder.setToken("access")
        val handshakeReceived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                handshakeReceived.countDown()
                release.await(5, TimeUnit.SECONDS)
                return upgrade()
            }
        }
        newClient()
        val events = CopyOnWriteArrayList<WsEvent>()
        appScope.launch { client.events.collect { events += it } }

        client.onStart(owner)
        assertTrue("handshake never reached the server", handshakeReceived.await(5, TimeUnit.SECONDS))

        client.disconnect() // logout pendant le handshake
        release.countDown() // le serveur accepte l'upgrade → onOpen d'une génération périmée

        assertTrue("stale socket was not closed", serverSide.ended.await(5, TimeUnit.SECONDS))
        Thread.sleep(300)
        assertEquals(WsConnectionState.Disconnected, client.connectionState.value)
        assertEquals(0, serverSide.messages.size) // token WS jamais envoyé
        assertTrue(events.none { it is WsEvent.Connected })
        assertFalse(client.hasPendingReconnectForTest)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("condition not met within ${timeoutMs}ms")
            Thread.sleep(10)
        }
    }

    /** Côté serveur du WebSocket MockWebServer. */
    private class ServerSide : WebSocketListener() {
        @Volatile var socket: WebSocket? = null
        val messages = CopyOnWriteArrayList<String>()
        val authReceived = CountDownLatch(1)
        val closing = CountDownLatch(1)
        /** Fermeture (close frame reçu) ou échec du socket serveur. */
        val ended = CountDownLatch(1)
        val closeCode = AtomicInteger(-1)

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            messages += text
            if (text.contains("\"token\"")) authReceived.countDown()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closeCode.set(code)
            webSocket.close(code, null)
            closing.countDown()
            ended.countDown()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            ended.countDown()
        }
    }
}
