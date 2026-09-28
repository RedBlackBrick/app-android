package com.tradingplatform.app.interceptor

import com.tradingplatform.app.data.api.AuthPaths
import com.tradingplatform.app.data.api.interceptor.AuthInterceptor
import com.tradingplatform.app.data.session.SessionManager
import com.tradingplatform.app.data.session.TokenHolder
import io.mockk.mockk
import io.mockk.verify
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Régression finding #2 (A-corr-1) : pendant un login 2FA, le TokenHolder est vide
 * (TotpRequiredException est levée avant tout setToken). POST /v1/auth/2fa/verify doit
 * atteindre le serveur sans Bearer, et ne pas déclencher de logout forcé.
 */
class AuthInterceptor2faTest {
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

    private fun post(path: String) = Request.Builder()
        .url(mockServer.url(path))
        .post("""{"temp_token":"t","code":"123456"}""".toRequestBody("application/json".toMediaType()))
        .build()

    @Test
    fun `2fa verify with empty TokenHolder reaches the server without forced logout`() {
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val response = buildClient().newCall(post(AuthPaths.TOTP_VERIFY)).execute()

        assertEquals(200, response.code)
        assertEquals(1, mockServer.requestCount)
        val recorded = mockServer.takeRequest()
        assertEquals(AuthPaths.TOTP_VERIFY, recorded.path)
        assertNull(recorded.getHeader("Authorization"))
        assertNotNull(recorded.getHeader("X-App-Version"))
        verify(exactly = 0) { sessionManager.notifyForcedLogout() }
    }

    @Test
    fun `verify-2fa alias with empty TokenHolder also reaches the server`() {
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        buildClient().newCall(post(AuthPaths.TOTP_VERIFY_ALIAS)).execute()

        assertEquals(1, mockServer.requestCount)
        verify(exactly = 0) { sessionManager.notifyForcedLogout() }
    }

    @Test
    fun `server 401 on 2fa verify is passed through, not converted into forced logout`() {
        mockServer.enqueue(MockResponse().setResponseCode(401).setBody("""{"error_code":"AUTH_1001"}"""))

        val response = buildClient().newCall(post(AuthPaths.TOTP_VERIFY)).execute()

        assertEquals(401, response.code)
        assertEquals(1, mockServer.requestCount)
        verify(exactly = 0) { sessionManager.notifyForcedLogout() }
    }

    @Test
    fun `non-public auth path with empty TokenHolder is still blocked`() {
        val response = buildClient().newCall(
            Request.Builder().url(mockServer.url("/v1/auth/me")).build()
        ).execute()

        assertEquals(401, response.code)
        assertEquals(0, mockServer.requestCount)
        verify(exactly = 1) { sessionManager.notifyForcedLogout() }
    }
}
