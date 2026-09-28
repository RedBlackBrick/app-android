package com.tradingplatform.app.interceptor

import com.tradingplatform.app.data.api.AuthPaths
import com.tradingplatform.app.data.api.interceptor.EncryptedCookieJar
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Régression NEW-cookie-2fa : pour un compte 2FA, /v1/auth/login ne renvoie aucun token ;
 * c'est /v1/auth/2fa/verify qui pose le cookie httpOnly refresh_token (backend
 * auth/router.py). Il doit être persisté puis renvoyé sur /v1/auth/refresh.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EncryptedCookieJar2faTest {
    private val mockServer = MockWebServer()
    private val dataStore = mockk<EncryptedDataStore>(relaxed = true)
    private val testScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
    private lateinit var cookieJar: EncryptedCookieJar

    @Before
    fun setUp() {
        mockServer.start()
        cookieJar = EncryptedCookieJar(dataStore, testScope)
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    private fun refreshCookie(value: String) = Cookie.Builder()
        .name("refresh_token")
        .value(value)
        .domain("10.42.0.1")
        .httpOnly()
        .build()

    @Test
    fun `refresh_token set on 2fa verify is persisted and sent on refresh`() {
        cookieJar.saveFromResponse(
            "https://10.42.0.1:443${AuthPaths.TOTP_VERIFY}".toHttpUrl(),
            listOf(refreshCookie("rt-2fa")),
        )

        coVerify(timeout = 2_000) { dataStore.saveCookie("refresh_token", "rt-2fa") }
        val sent = cookieJar.loadForRequest("https://10.42.0.1:443${AuthPaths.REFRESH}".toHttpUrl())
        assertEquals(1, sent.size)
        assertEquals("refresh_token", sent[0].name)
        assertEquals("rt-2fa", sent[0].value)
    }

    @Test
    fun `refresh_token set on verify-2fa alias is persisted`() {
        cookieJar.saveFromResponse(
            "https://10.42.0.1:443${AuthPaths.TOTP_VERIFY_ALIAS}".toHttpUrl(),
            listOf(refreshCookie("rt-alias")),
        )

        coVerify(timeout = 2_000) { dataStore.saveCookie("refresh_token", "rt-alias") }
    }

    @Test
    fun `refresh_token is not sent on 2fa verify itself`() {
        cookieJar.saveFromResponse(
            "https://10.42.0.1:443${AuthPaths.TOTP_VERIFY}".toHttpUrl(),
            listOf(refreshCookie("rt-2fa")),
        )

        val sent = cookieJar.loadForRequest("https://10.42.0.1:443${AuthPaths.TOTP_VERIFY}".toHttpUrl())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `end to end - Set-Cookie on 2fa verify response is replayed on refresh request`() {
        val client = OkHttpClient.Builder().cookieJar(cookieJar).build()
        val json = "application/json".toMediaType()
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Set-Cookie", "refresh_token=rt-e2e; Path=/; HttpOnly")
                .setBody("{}")
        )
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        client.newCall(
            Request.Builder()
                .url(mockServer.url(AuthPaths.TOTP_VERIFY))
                .post("{}".toRequestBody(json))
                .build()
        ).execute().close()
        client.newCall(
            Request.Builder()
                .url(mockServer.url(AuthPaths.REFRESH))
                .post("".toRequestBody(json))
                .build()
        ).execute().close()

        val verifyRequest = mockServer.takeRequest()
        assertNull(verifyRequest.getHeader("Cookie"))
        val refreshRequest = mockServer.takeRequest()
        val cookieHeader = refreshRequest.getHeader("Cookie")
        assertNotNull(cookieHeader)
        assertTrue(cookieHeader!!.contains("refresh_token=rt-e2e"))
        coVerify(timeout = 2_000) { dataStore.saveCookie("refresh_token", "rt-e2e") }
    }
}
