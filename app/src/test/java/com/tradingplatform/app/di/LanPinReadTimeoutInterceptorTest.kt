package com.tradingplatform.app.di

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * `POST /pin` est synchrone côté Radxa (≈ 55 s sur fb847ec, ≈ 113 s sur 93fdc58) alors que le
 * client LAN a un `readTimeout` global de 10 s. Seul `/pin` doit être relevé au niveau de la durée
 * de vie de la session VPS (120 s) ; `/status` garde les 10 s.
 */
class LanPinReadTimeoutInterceptorTest {

    private lateinit var server: MockWebServer
    private var observedReadTimeoutMs = -1

    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        val probe = Interceptor { chain ->
            observedReadTimeoutMs = chain.readTimeoutMillis()
            chain.proceed(chain.request())
        }
        client = OkHttpClient.Builder()
            .readTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(NetworkModule.lanPinReadTimeoutInterceptor())
            .addInterceptor(probe) // s'exécute après l'intercepteur testé : voit le timeout effectif
            .build()
    }

    @After
    fun tearDown() {
        client.dispatcher.executorService.shutdownNow()
        server.shutdown()
    }

    private fun get(path: String) {
        server.enqueue(MockResponse().setBody("ok"))
        client.newCall(okhttp3.Request.Builder().url(server.url(path)).build()).execute().close()
    }

    @Test
    fun `pin gets the 120 s session-lifetime read timeout`() {
        get("/pin")

        assertEquals(NetworkModule.LAN_PIN_READ_TIMEOUT_SECONDS * 1000, observedReadTimeoutMs)
        assertEquals(120_000, observedReadTimeoutMs)
    }

    @Test
    fun `status keeps the global 10 s read timeout`() {
        get("/status?session_id=abc")

        assertEquals(10_000, observedReadTimeoutMs)
    }

    @Test
    fun `identity keeps the global 10 s read timeout`() {
        get("/identity")

        assertEquals(10_000, observedReadTimeoutMs)
    }

    @Test
    fun `a path that merely contains pin keeps the global read timeout`() {
        get("/pinned")

        assertEquals(10_000, observedReadTimeoutMs)
    }
}
