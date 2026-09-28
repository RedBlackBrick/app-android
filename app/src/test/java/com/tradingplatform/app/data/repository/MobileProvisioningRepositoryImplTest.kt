package com.tradingplatform.app.data.repository

import com.tradingplatform.app.domain.model.MobileProvisioningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Covers finding B-pairing-1: [MobileProvisioningRepositoryImpl.register] used to run its
 * blocking `OkHttpClient.execute()` call directly on the caller's dispatcher (`Dispatchers.
 * Main.immediate` in practice, since it is invoked from `viewModelScope.launch` via
 * `ProvisionMobileVpnUseCase`) — throwing `NetworkOnMainThreadException` on every real device
 * and blocking onboarding.
 *
 * [MobileProvisioningRepositoryImpl] now wraps the call in `withContext(io)` where `io` is
 * `@IoDispatcher`. These tests inject the *real* `Dispatchers.IO` (the production value) — not
 * a virtual-time `TestDispatcher` — so the thread-identity assertion below is a genuine proof
 * that the blocking call is moved off the caller's thread, not a simulation.
 *
 * A test-only seam (`testClientOverride` / `testBaseUrlOverride`, `internal` on the impl) lets
 * these tests point `register()` at a plain-http [MockWebServer] instance. Production
 * certificate pinning is untouched — the seam is only consulted when explicitly set, and
 * pinning is irrelevant to a non-TLS test transport regardless.
 */
class MobileProvisioningRepositoryImplTest {

    private val mockServer = MockWebServer()
    private lateinit var repository: MobileProvisioningRepositoryImpl
    private lateinit var executionThread: AtomicReference<Thread?>

    @Before
    fun setUp() {
        mockServer.start()
        executionThread = AtomicReference(null)

        repository = MobileProvisioningRepositoryImpl(io = Dispatchers.IO).apply {
            testBaseUrlOverride = mockServer.url("/").toString().trimEnd('/')
            testClientOverride = OkHttpClient.Builder()
                .addInterceptor(
                    Interceptor { chain ->
                        executionThread.set(Thread.currentThread())
                        chain.proceed(chain.request())
                    },
                )
                .build()
        }
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun `register executes the blocking network call off the caller thread`() = runTest {
        val callerThread = Thread.currentThread()
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(successBody()))

        val result = repository.register(
            host = "vps.example.com",
            provisioningId = "prov-1",
            wgPubkey = "pubkey",
            pinProof = "proof",
            nonce = "nonce",
            deviceLabel = null,
            fcmToken = null,
        )

        assertTrue("register() must succeed", result.isSuccess)
        val executedOn = executionThread.get()
        assertNotNull("interceptor must have recorded the execution thread", executedOn)
        assertNotEquals(
            "OkHttp execute() must not run on the caller's thread — withContext(io) must switch it",
            callerThread,
            executedOn,
        )
    }

    @Test
    fun `register maps a successful response to MobileProvisioningResult`() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(successBody()))

        val result = repository.register(
            host = "vps.example.com",
            provisioningId = "prov-1",
            wgPubkey = "pubkey-abc",
            pinProof = "proof-xyz",
            nonce = "nonce-123",
            deviceLabel = "Pixel 8",
            fcmToken = "fcm-token",
        )

        assertEquals(
            MobileProvisioningResult(
                vpnPeerId = 42L,
                tunnelIp = "10.42.0.7",
                dns = "10.42.0.1",
                allowedIps = "0.0.0.0/0",
                serverPubkey = "server-pubkey",
                endpoint = "vps.example.com:51820",
            ),
            result.getOrThrow(),
        )

        val recorded = mockServer.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/me/mobile-provisioning/prov-1/register", recorded.path)
        val requestJson = JSONObject(recorded.body.readUtf8())
        assertEquals("pubkey-abc", requestJson.getString("wg_pubkey"))
        assertEquals("proof-xyz", requestJson.getString("pin_proof"))
        assertEquals("nonce-123", requestJson.getString("nonce"))
        assertEquals("Pixel 8", requestJson.getString("device_label"))
        assertEquals("fcm-token", requestJson.getString("fcm_token"))
    }

    @Test
    fun `register omits optional fields when null or blank`() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(successBody()))

        repository.register(
            host = "vps.example.com",
            provisioningId = "prov-1",
            wgPubkey = "pubkey",
            pinProof = "proof",
            nonce = "nonce",
            deviceLabel = null,
            fcmToken = "  ",
        )

        val requestJson = JSONObject(mockServer.takeRequest().body.readUtf8())
        assertTrue(!requestJson.has("device_label"))
        assertTrue(!requestJson.has("fcm_token"))
    }

    @Test
    fun `register returns Result failure on a 4xx response`() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setBody("""{"error":"already_registered"}"""),
        )

        val result = repository.register(
            host = "vps.example.com",
            provisioningId = "prov-1",
            wgPubkey = "pubkey",
            pinProof = "proof",
            nonce = "nonce",
            deviceLabel = null,
            fcmToken = null,
        )

        assertTrue("register() must fail on a 4xx response", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is MobileProvisioningHttpException)
        error as MobileProvisioningHttpException
        assertEquals(409, error.code)
        assertTrue(error.body.contains("already_registered"))
    }

    @Test
    fun `register returns Result failure on a non-JSON success body`() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("not json"))

        val result = repository.register(
            host = "vps.example.com",
            provisioningId = "prov-1",
            wgPubkey = "pubkey",
            pinProof = "proof",
            nonce = "nonce",
            deviceLabel = null,
            fcmToken = null,
        )

        assertTrue("register() must fail on a non-JSON 200 body", result.isFailure)
    }

    private fun successBody(): String = """
        {
          "vpn_peer_id": 42,
          "tunnel_ip": "10.42.0.7",
          "dns": "10.42.0.1",
          "allowed_ips": "0.0.0.0/0",
          "server_pubkey": "server-pubkey",
          "endpoint": "vps.example.com:51820"
        }
    """.trimIndent()
}
