package com.tradingplatform.app.interceptor

import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.tradingplatform.app.data.api.AuthApi
import com.tradingplatform.app.data.api.interceptor.EncryptedCookieJar
import com.tradingplatform.app.data.api.interceptor.TokenAuthenticator
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.data.local.db.AppDatabase
import com.tradingplatform.app.data.session.SessionManager
import com.tradingplatform.app.data.session.TokenHolder
import com.tradingplatform.app.di.NetworkModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for [TokenAuthenticator] — audit finding #15 (A-conc-1).
 *
 * A real [TokenAuthenticator] is wired to a MockWebServer:
 * - `/v1/portfolios/x` answers 401 unless the request carries `Authorization: Bearer new`
 *   (or always 401 when [resourceAlways401] is set);
 * - `/v1/auth/refresh` optionally sleeps, counts its calls and returns `{"access_token":"new"}`
 *   (or 401 when [refreshStatus] is 401).
 *
 * The refresh [AuthApi] is built on a bare client (no Authenticator) — the shape of the
 * production `@Named("refresh")` client (#16), minus VPN/pinning/timeout interceptors.
 * The resource client carries only the authenticator; the Authorization header is set
 * by hand to simulate what AuthInterceptor sent at the time of the original request.
 *
 * Cases:
 * - (a) parallel 401s → exactly one refresh, every request retried with the new token;
 * - (b) stale bearer → retried with the holder's token, no refresh;
 * - (c) refresh 401 → forced logout (regression guard);
 * - (d) persistent 401 → one refresh, then the priorResponse guard gives up;
 * - (e) refresh slower than the wait timeout → null, but the refresh completes in the
 *   background and fills TokenHolder (the timeout cancels only the waiter);
 * - (f) 401 on /v1/auth/login is a business failure → no refresh, no logout.
 */
class TokenAuthenticatorTest {

    private val server = MockWebServer()
    private val refreshCalls = AtomicInteger(0)
    private val resourceAuthHeaders = CopyOnWriteArrayList<String?>()

    @Volatile private var refreshDelayMs = 0L
    @Volatile private var refreshStatus = 200
    @Volatile private var resourceAlways401 = false

    private val tokenHolder = TokenHolder()
    private val dataStore = mockk<EncryptedDataStore>(relaxed = true)
    private val sessionManager = mockk<SessionManager>(relaxed = true)
    private val appDatabase = mockk<AppDatabase>(relaxed = true)
    private val cookieJar = mockk<EncryptedCookieJar>(relaxed = true)
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var authenticator: TokenAuthenticator
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        // handleLogout() reports to Crashlytics through the static singleton — no
        // FirebaseApp on the JVM, so stub it.
        mockkStatic(FirebaseCrashlytics::class)
        every { FirebaseCrashlytics.getInstance() } returns mockk(relaxed = true)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path?.substringBefore('?')) {
                    "/v1/auth/refresh" -> {
                        refreshCalls.incrementAndGet()
                        if (refreshDelayMs > 0) Thread.sleep(refreshDelayMs)
                        if (refreshStatus == 200) {
                            MockResponse().setResponseCode(200).setBody(
                                """{"access_token":"new","token_type":"bearer","expires_in":900}"""
                            )
                        } else {
                            MockResponse().setResponseCode(refreshStatus)
                                .setBody("""{"detail":{"code":"AUTH_1003"}}""")
                        }
                    }
                    "/v1/portfolios/x" -> {
                        val auth = request.getHeader("Authorization")
                        resourceAuthHeaders += auth
                        if (!resourceAlways401 && auth == "Bearer new") {
                            MockResponse().setResponseCode(200).setBody("{}")
                        } else {
                            MockResponse().setResponseCode(401)
                                .setBody("""{"detail":{"code":"AUTH_1002"}}""")
                        }
                    }
                    "/v1/auth/login" -> MockResponse().setResponseCode(401)
                        .setBody("""{"detail":{"code":"AUTH_1001"}}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()

        val authApi = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(MoshiConverterFactory.create(NetworkModule.provideMoshi()))
            .build()
            .create(AuthApi::class.java)

        authenticator = TokenAuthenticator(
            applicationScope = applicationScope,
            tokenHolder = tokenHolder,
            dataStore = dataStore,
            authApi = authApi,
            sessionManager = sessionManager,
            appDatabase = appDatabase,
            cookieJar = cookieJar,
        )
        client = OkHttpClient.Builder()
            .authenticator(authenticator)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
        applicationScope.cancel()
        unmockkAll()
    }

    private fun get(bearer: String): Response = client.newCall(
        Request.Builder()
            .url(server.url("/v1/portfolios/x"))
            .header("Authorization", "Bearer $bearer")
            .build()
    ).execute()

    // (a) ───────────────────────────────────────────────────────────────────

    @Test
    fun `parallel 401s trigger exactly one refresh`() {
        tokenHolder.setToken("old")
        refreshDelayMs = 300
        val parallelism = 8
        val pool = Executors.newFixedThreadPool(parallelism)
        val startGate = CountDownLatch(1)
        try {
            val futures = (1..parallelism).map {
                pool.submit<Int> {
                    startGate.await()
                    get("old").use { it.code }
                }
            }
            startGate.countDown()
            val codes = futures.map { it.get(30, TimeUnit.SECONDS) }

            assertEquals(
                "expected a single POST /v1/auth/refresh for $parallelism concurrent 401s",
                1,
                refreshCalls.get(),
            )
            assertTrue("every request should succeed after the refresh: $codes", codes.all { it == 200 })
            assertEquals("new", tokenHolder.accessToken)
        } finally {
            pool.shutdownNow()
        }
    }

    // (b) ───────────────────────────────────────────────────────────────────

    @Test
    fun `401 carrying a stale bearer is retried with the current token without refreshing`() {
        // Another request already refreshed: the holder has "new" but this request was
        // sent with an older token.
        tokenHolder.setToken("new")

        val code = get("stale").use { it.code }

        assertEquals("no refresh expected when the holder already has a newer token", 0, refreshCalls.get())
        assertEquals(200, code)
        assertEquals("Bearer new", resourceAuthHeaders.last())
    }

    // (c) ───────────────────────────────────────────────────────────────────

    @Test
    fun `refresh rejected with 401 gives up and forces logout`() {
        tokenHolder.setToken("old")
        refreshStatus = 401

        val code = get("old").use { it.code }

        assertEquals(401, code)
        assertEquals(1, refreshCalls.get())
        assertNull(tokenHolder.accessToken)
        verify(atLeast = 1) { cookieJar.clear() }
        verify(atLeast = 1) { sessionManager.notifyForcedLogout() }
    }

    // (d) ───────────────────────────────────────────────────────────────────

    @Test
    fun `resource still 401 after a successful refresh does not refresh again`() {
        tokenHolder.setToken("old")
        resourceAlways401 = true

        // Current code loops through OkHttp's 20 follow-ups and throws
        // ProtocolException("Too many follow-up requests") — tolerate both outcomes,
        // the assertion is on the refresh count.
        val result = runCatching { get("old").use { it.code } }

        assertTrue(
            "at most one refresh per original request, got ${refreshCalls.get()} (result=$result)",
            refreshCalls.get() <= 1,
        )
        assertEquals(401, result.getOrNull())
    }

    // (e) ───────────────────────────────────────────────────────────────────

    @Test
    fun `refresh slower than the wait timeout gives up but still fills TokenHolder`() {
        tokenHolder.setToken("old")
        authenticator.authenticateTimeoutMs = 200
        refreshDelayMs = 1_000

        val code = get("old").use { it.code }

        assertEquals("the waiter times out → original 401 surfaces", 401, code)
        verify(exactly = 0) { sessionManager.notifyForcedLogout() }

        // The shared refresh was not cancelled by the waiter's timeout: it completes in
        // applicationScope and updates the holder.
        val deadline = System.currentTimeMillis() + 5_000
        while (tokenHolder.accessToken != "new" && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertEquals("new", tokenHolder.accessToken)
        assertEquals(1, refreshCalls.get())

        // A retry from the UI (still carrying the old bearer) takes the stale-bearer fast path.
        val retryCode = get("old").use { it.code }
        assertEquals(200, retryCode)
        assertEquals("no second refresh after the background one completed", 1, refreshCalls.get())
    }

    // (f) ───────────────────────────────────────────────────────────────────

    @Test
    fun `401 on login is a business failure - no refresh and no logout`() {
        tokenHolder.setToken("old")

        val code = client.newCall(
            Request.Builder()
                .url(server.url("/v1/auth/login"))
                .post("""{"email":"a@b.c","password":"x"}""".toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { it.code }

        assertEquals(401, code)
        assertEquals(0, refreshCalls.get())
        assertEquals("old", tokenHolder.accessToken)
        verify(exactly = 0) { sessionManager.notifyForcedLogout() }
        verify(exactly = 0) { cookieJar.clear() }
    }
}
