package com.tradingplatform.app.di

import com.squareup.moshi.Moshi
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Le client `@Named("write")` dérive du client principal et ne diffère que par
 * `retryOnConnectionFailure = false` : une écriture n'est jamais rejouée par OkHttp (docs/write-actions.md).
 */
class WriteNetworkModuleTest {

    private lateinit var server: MockWebServer
    private val created = mutableListOf<OkHttpClient>()

    private val first = Interceptor { chain -> chain.proceed(chain.request()) }
    private val second = Interceptor { chain -> chain.proceed(chain.request()) }
    private val authenticator = object : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? = null
    }

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        created.forEach { it.dispatcher.executorService.shutdownNow() }
        server.shutdown()
    }

    /** Imite le client principal : plusieurs intercepteurs, Authenticator, CookieJar, timeouts. */
    private fun baseClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(first)
        .addInterceptor(second)
        .authenticator(authenticator)
        .cookieJar(CookieJar.NO_COOKIES)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
        .also { created += it }

    @Test
    fun `write client disables connection-failure retries while the base client keeps the default`() {
        val base = baseClient()

        val write = WriteNetworkModule.buildWriteClient(base).also { created += it }

        assertTrue(base.retryOnConnectionFailure)
        assertFalse(write.retryOnConnectionFailure)
    }

    @Test
    fun `write client inherits interceptors in order, authenticator, cookie jar, pool and timeouts`() {
        val base = baseClient()

        val write = WriteNetworkModule.buildWriteClient(base).also { created += it }

        assertEquals(listOf(first, second), write.interceptors)
        assertSame(base.authenticator, write.authenticator)
        assertSame(base.cookieJar, write.cookieJar)
        assertSame(base.connectionPool, write.connectionPool)
        assertSame(base.dispatcher, write.dispatcher)
        assertEquals(30_000, write.connectTimeoutMillis)
        assertEquals(30_000, write.readTimeoutMillis)
        assertEquals(30_000, write.writeTimeoutMillis)
    }

    @Test
    fun `write client does not replay a POST when the connection drops after the request was sent`() {
        // Si le client rejouait, la 2e réponse (200) sortirait et l'appel réussirait.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val write = WriteNetworkModule.buildWriteClient(baseClient()).also { created += it }
        val request = Request.Builder()
            .url(server.url("/v1/orders/42/cancel"))
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()

        assertThrows(IOException::class.java) { write.newCall(request).execute().close() }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `write retrofit uses the given base url and the write client`() {
        val write = WriteNetworkModule.buildWriteClient(baseClient()).also { created += it }

        val retrofit = WriteNetworkModule.buildWriteRetrofit(
            baseUrl = "https://vps.example.test/",
            writeClient = write,
            moshi = Moshi.Builder().build(),
        )

        assertEquals("https://vps.example.test/", retrofit.baseUrl().toString())
        assertSame(write, retrofit.callFactory())
    }
}
