package com.tradingplatform.app.interceptor

import com.tradingplatform.app.data.api.interceptor.EncryptedCookieJar
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EncryptedCookieJarTest {
    private val dataStore = mockk<EncryptedDataStore>(relaxed = true)
    private lateinit var cookieJar: EncryptedCookieJar
    private val testScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())

    @Before
    fun setUp() {
        cookieJar = EncryptedCookieJar(dataStore, testScope)
    }

    @Test
    fun `saves refresh_token cookie on login path`() = runTest {
        val url = "https://10.42.0.1:443/v1/auth/login".toHttpUrl()
        val cookie = Cookie.Builder()
            .name("refresh_token")
            .value("refresh123")
            .domain("10.42.0.1")
            .httpOnly()
            .build()

        cookieJar.saveFromResponse(url, listOf(cookie))

        coVerify { dataStore.saveCookie("refresh_token", any()) }
    }

    @Test
    fun `does not save cookies on non-auth paths`() = runTest {
        val url = "https://10.42.0.1:443/v1/portfolios/1/positions".toHttpUrl()
        val cookie = Cookie.Builder()
            .name("refresh_token")
            .value("refresh123")
            .domain("10.42.0.1")
            .build()

        cookieJar.saveFromResponse(url, listOf(cookie))

        coVerify(exactly = 0) { dataStore.saveCookie(any(), any()) }
    }

    @Test
    fun `loads cookies only for refresh path`() = runTest {
        coEvery { dataStore.loadCookies() } returns emptyList()
        cookieJar.preload()

        val refreshUrl = "https://10.42.0.1:443/v1/auth/refresh".toHttpUrl()
        val otherUrl = "https://10.42.0.1:443/v1/portfolios".toHttpUrl()

        cookieJar.loadForRequest(refreshUrl)
        cookieJar.loadForRequest(otherUrl)
        // loadCookies() n'est plus appelé par loadForRequest (cache mémoire) —
        // seul preload() le déclenche.
        coVerify(exactly = 1) { dataStore.loadCookies() }
    }

    // ── Audit NEW-cookie-2fa — refresh cookie set by /v1/auth/2fa/verify must be kept ──
    //
    // For TOTP accounts, /v1/auth/login returns no tokens; the backend sets the
    // refresh_token cookie on the 2FA verify response (auth/router.py verify_2fa).
    // Expected RED on current code: AUTH_SAVE_PATHS = {login, refresh} only, so the cookie
    // is dropped and the first refresh after the access-token TTL fails → forced logout.

    @Test
    fun `refresh_token cookie set by 2fa verify is sent on the next refresh`() = runTest {
        val verifyUrl = "https://10.42.0.1:443/v1/auth/2fa/verify".toHttpUrl()
        val cookie = Cookie.Builder()
            .name("refresh_token")
            .value("refresh-from-2fa")
            .domain("10.42.0.1")
            .httpOnly()
            .build()

        cookieJar.saveFromResponse(verifyUrl, listOf(cookie))

        val sent = cookieJar.loadForRequest("https://10.42.0.1:443/v1/auth/refresh".toHttpUrl())
        assertEquals(1, sent.size)
        assertEquals("refresh_token", sent[0].name)
        assertEquals("refresh-from-2fa", sent[0].value)
        // Persisted asynchronously on Dispatchers.IO — allow a short grace period.
        coVerify(timeout = 1_000) { dataStore.saveCookie("refresh_token", "refresh-from-2fa") }
    }
}
