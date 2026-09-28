package com.tradingplatform.app.interceptor

import com.tradingplatform.app.di.NetworkModule
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tests for [NetworkModule.debugLogger] — audit finding A-sec-3.
 *
 * Auth paths ([com.tradingplatform.app.data.api.AuthPaths.isSensitive]) must only log
 * headers (no password / tokens from bodies), sensitive headers are always redacted,
 * other paths keep their body in the debug log.
 */
class DebugLoggerTest {

    private val server = MockWebServer()
    private val lines = CopyOnWriteArrayList<String>()
    private val capturingLogger = object : HttpLoggingInterceptor.Logger {
        override fun log(message: String) {
            lines += message
        }
    }

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.path?.substringBefore('?')) {
                    "/v1/auth/login" -> MockResponse().setResponseCode(200)
                        .addHeader("Set-Cookie", "refresh_token=secret-refresh; HttpOnly; Path=/v1/auth")
                        .setBody("""{"access_token":"secret-access","token_type":"bearer"}""")
                    "/v1/portfolios" -> MockResponse().setResponseCode(200)
                        .setBody("""[{"id":42,"name":"main"}]""")
                    else -> MockResponse().setResponseCode(404)
                }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(headersOnly: Boolean) = OkHttpClient.Builder()
        .addInterceptor(NetworkModule.debugLogger(headersOnly = headersOnly, logger = capturingLogger))
        .build()

    @Test
    fun `login logs headers only and redacts cookies`() {
        client(headersOnly = false).newCall(
            Request.Builder()
                .url(server.url("/v1/auth/login"))
                .header("Cookie", "refresh_token=secret-cookie")
                .post(
                    """{"email":"u@example.com","password":"hunter2"}"""
                        .toRequestBody("application/json".toMediaType())
                )
                .build()
        ).execute().use { it.body?.string() }

        val log = lines.joinToString("\n")
        listOf("password", "hunter2", "access_token", "secret-access", "secret-refresh", "secret-cookie")
            .forEach { secret -> assertFalse("'$secret' leaked into the debug log:\n$log", log.contains(secret)) }

        val setCookie = lines.single { it.startsWith("Set-Cookie:", ignoreCase = true) }
        assertTrue("Set-Cookie must be redacted: $setCookie", setCookie.contains("██"))
        val cookie = lines.single { it.startsWith("Cookie:", ignoreCase = true) }
        assertTrue("Cookie must be redacted: $cookie", cookie.contains("██"))
    }

    @Test
    fun `non sensitive path logs the body and redacts Authorization`() {
        client(headersOnly = false).newCall(
            Request.Builder()
                .url(server.url("/v1/portfolios"))
                .header("Authorization", "Bearer secret-bearer")
                .build()
        ).execute().use { it.body?.string() }

        val log = lines.joinToString("\n")
        assertTrue("body expected in the debug log:\n$log", log.contains(""""id":42"""))
        assertFalse("bearer leaked:\n$log", log.contains("secret-bearer"))
        val authorization = lines.single { it.startsWith("Authorization:") }
        assertTrue("Authorization must be redacted: $authorization", authorization.contains("██"))
    }

    @Test
    fun `headers-only logger never logs bodies`() {
        client(headersOnly = true).newCall(
            Request.Builder().url(server.url("/v1/portfolios")).build()
        ).execute().use { it.body?.string() }

        val log = lines.joinToString("\n")
        assertFalse("body must not be logged in headers-only mode:\n$log", log.contains(""""id":42"""))
    }
}
