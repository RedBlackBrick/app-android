package com.tradingplatform.app.interceptor

import com.tradingplatform.app.data.api.interceptor.AuthInterceptor
import com.tradingplatform.app.data.session.SessionManager
import com.tradingplatform.app.data.session.TokenHolder
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * Tests d'intégration pour [AuthInterceptor] après refactor : l'interceptor ne fait plus
 * de fallback disque via runBlocking. Il lit uniquement le cache mémoire [TokenHolder]
 * (pré-chargé par [TradingApplication] au startup) et déclenche un logout forcé si absent.
 */
class AuthInterceptorTest {
    private val mockServer = MockWebServer()
    private val tokenHolder = TokenHolder()
    private val sessionManager = mockk<SessionManager>(relaxed = true)

    @Before
    fun setUp() {
        mockServer.start()
        tokenHolder.clear()
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    private fun buildClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(tokenHolder, sessionManager))
        .build()

    @Test
    fun `adds Authorization header when TokenHolder is populated`() = runTest {
        tokenHolder.setToken("test_token_123")
        mockServer.enqueue(MockResponse().setResponseCode(200))

        buildClient().newCall(
            Request.Builder().url(mockServer.url("/test")).build()
        ).execute()

        val recorded = mockServer.takeRequest()
        assertEquals("Bearer test_token_123", recorded.getHeader("Authorization"))
        assertNotNull(recorded.getHeader("X-App-Version"))
    }

    @Test
    fun `when TokenHolder is empty forced logout is triggered and request never hits network`() = runTest {
        // TokenHolder vide → logout forcé + réponse 401 synthétique
        val response = buildClient().newCall(
            Request.Builder().url(mockServer.url("/test")).build()
        ).execute()

        verify(exactly = 1) { sessionManager.notifyForcedLogout() }
        assertEquals(401, response.code)
        assertEquals(0, mockServer.requestCount)
    }

    @Test
    fun `public paths bypass the token check and reach the server`() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(200))

        buildClient().newCall(
            Request.Builder().url(mockServer.url("/v1/auth/login")).build()
        ).execute()

        val recorded = mockServer.takeRequest()
        // No Authorization header on public path
        assertEquals(null, recorded.getHeader("Authorization"))
        // But X-App-Version is still present
        assertNotNull(recorded.getHeader("X-App-Version"))
        verify(exactly = 0) { sessionManager.notifyForcedLogout() }
    }

    @Test
    fun `consecutive requests without token each trigger a logout notification`() = runTest {
        repeat(2) {
            buildClient().newCall(
                Request.Builder().url(mockServer.url("/test")).build()
            ).execute()
        }

        verify(exactly = 2) { sessionManager.notifyForcedLogout() }
        assertEquals(0, mockServer.requestCount)
    }

    @Test
    fun `SessionManager is never called when a valid token is cached`() = runTest {
        tokenHolder.setToken("valid_jwt")
        mockServer.enqueue(MockResponse().setResponseCode(200))

        buildClient().newCall(
            Request.Builder().url(mockServer.url("/api/portfolio")).build()
        ).execute()

        verify(exactly = 0) { sessionManager.notifyForcedLogout() }
        verify(exactly = 0) { sessionManager.notifyKeystoreCorruption() }
    }
}
