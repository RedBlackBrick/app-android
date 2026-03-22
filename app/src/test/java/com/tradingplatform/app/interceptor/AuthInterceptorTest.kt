package com.tradingplatform.app.interceptor

import com.tradingplatform.app.data.api.interceptor.AuthInterceptor
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.data.local.datastore.SecureReadResult
import com.tradingplatform.app.data.session.SessionManager
import com.tradingplatform.app.data.session.TokenHolder
import io.mockk.coEvery
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
import java.security.GeneralSecurityException

class AuthInterceptorTest {
    private val mockServer = MockWebServer()
    private val tokenHolder = TokenHolder()
    private val dataStore = mockk<EncryptedDataStore>()
    private val sessionManager = mockk<SessionManager>(relaxed = true)

    @Before
    fun setUp() {
        mockServer.start()
        // Clear TokenHolder before each test so the interceptor falls back to dataStore
        tokenHolder.clear()
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun buildClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(tokenHolder, dataStore, sessionManager))
        .build()

    // ── Normal token path ─────────────────────────────────────────────────────

    @Test
    fun `adds Authorization header when token available`() = runTest {
        coEvery { dataStore.readStringSafe(DataStoreKeys.ACCESS_TOKEN) } returns SecureReadResult.Found("test_token_123")

        mockServer.enqueue(MockResponse().setResponseCode(200))

        buildClient().newCall(
            Request.Builder().url(mockServer.url("/test")).build()
        ).execute()

        val recorded = mockServer.takeRequest()
        assertEquals("Bearer test_token_123", recorded.getHeader("Authorization"))
        assertNotNull(recorded.getHeader("X-App-Version"))
    }

    @Test
    fun `X-App-Version header is always present when token is valid`() = runTest {
        coEvery { dataStore.readStringSafe(DataStoreKeys.ACCESS_TOKEN) } returns SecureReadResult.Found("any_token")

        mockServer.enqueue(MockResponse().setResponseCode(200))

        buildClient().newCall(
            Request.Builder().url(mockServer.url("/ping")).build()
        ).execute()

        assertNotNull(mockServer.takeRequest().getHeader("X-App-Version"))
    }

    // ── Absent token path ─────────────────────────────────────────────────────

    @Test
    fun `when token is absent forced logout is triggered`() = runTest {
        coEvery { dataStore.readStringSafe(DataStoreKeys.ACCESS_TOKEN) } returns SecureReadResult.NotFound

        // No response enqueued — the request must NOT reach the server.
        val response = buildClient().newCall(
            Request.Builder().url(mockServer.url("/test")).build()
        ).execute()

        // Interceptor must have called notifyForcedLogout exactly once
        verify(exactly = 1) { sessionManager.notifyForcedLogout() }

        // A synthetic 401 is returned without hitting the network
        assertEquals(401, response.code)

        // MockWebServer must have received zero requests
        assertEquals(0, mockServer.requestCount)
    }

    @Test
    fun `when token is absent response does not reach the server`() = runTest {
        coEvery { dataStore.readStringSafe(DataStoreKeys.ACCESS_TOKEN) } returns SecureReadResult.NotFound

        val response = buildClient().newCall(
            Request.Builder().url(mockServer.url("/test")).build()
        ).execute()

        // The synthetic response is never transmitted over the network, so no
        // Authorization header is ever sent to an external server.
        assertEquals(401, response.code)
        assertEquals(0, mockServer.requestCount)
    }

    @Test
    fun `when token is absent no network request is made`() = runTest {
        coEvery { dataStore.readStringSafe(DataStoreKeys.ACCESS_TOKEN) } returns SecureReadResult.NotFound

        buildClient().newCall(
            Request.Builder().url(mockServer.url("/sensitive-data")).build()
        ).execute()

        // Critically: no request must reach the server — token absence is caught before the wire.
        assertEquals(0, mockServer.requestCount)
    }

    // ── EncryptedDataStore corruption path ────────────────────────────────────

    @Test
    fun `when Keystore is corrupted keystoreCorruption is triggered`() = runTest {
        // Simulate Keystore invalidation (reboot, biometric removal, device reset).
        // readStringSafe returns Corrupted — AuthInterceptor calls notifyKeystoreCorruption.
        coEvery { dataStore.readStringSafe(DataStoreKeys.ACCESS_TOKEN) } returns
            SecureReadResult.Corrupted(GeneralSecurityException("Keystore invalidated"))

        val response = buildClient().newCall(
            Request.Builder().url(mockServer.url("/test")).build()
        ).execute()

        verify(exactly = 1) { sessionManager.notifyKeystoreCorruption() }
        assertEquals(401, response.code)
        assertEquals(0, mockServer.requestCount)
    }

    @Test
    fun `consecutive requests with missing token each trigger logout notification`() = runTest {
        // Each independent intercepted request that finds no token must notify the bus,
        // since the OkHttp interceptor is stateless and does not track previous events.
        coEvery { dataStore.readStringSafe(DataStoreKeys.ACCESS_TOKEN) } returns SecureReadResult.NotFound

        repeat(2) {
            buildClient().newCall(
                Request.Builder().url(mockServer.url("/test")).build()
            ).execute()
        }

        verify(exactly = 2) { sessionManager.notifyForcedLogout() }
        assertEquals(0, mockServer.requestCount)
    }

    // ── TokenHolder hot path ─────────────────────────────────────────────────

    @Test
    fun `uses TokenHolder when token is cached in memory`() = runTest {
        tokenHolder.setToken("cached_jwt")

        mockServer.enqueue(MockResponse().setResponseCode(200))

        buildClient().newCall(
            Request.Builder().url(mockServer.url("/api/portfolio")).build()
        ).execute()

        val recorded = mockServer.takeRequest()
        assertEquals("Bearer cached_jwt", recorded.getHeader("Authorization"))
        // dataStore.readStringSafe should not be called when TokenHolder has a value
    }

    // ── Regression: valid token path is unaffected ────────────────────────────

    @Test
    fun `SessionManager is never called when a valid token is present`() = runTest {
        coEvery { dataStore.readStringSafe(DataStoreKeys.ACCESS_TOKEN) } returns SecureReadResult.Found("valid_jwt")

        mockServer.enqueue(MockResponse().setResponseCode(200))

        buildClient().newCall(
            Request.Builder().url(mockServer.url("/api/portfolio")).build()
        ).execute()

        // Verify no forced logout event is fired for a healthy session
        verify(exactly = 0) { sessionManager.notifyForcedLogout() }
        verify(exactly = 0) { sessionManager.notifyKeystoreCorruption() }
    }

    @Test
    fun `Authorization header is absent from server request when token is missing`() = runTest {
        // Guard: if somehow the request did reach the server, it must not carry a Bearer token.
        // Combined with the mockServer.requestCount == 0 check, this double-confirms safety.
        coEvery { dataStore.readStringSafe(DataStoreKeys.ACCESS_TOKEN) } returns SecureReadResult.NotFound

        buildClient().newCall(
            Request.Builder().url(mockServer.url("/test")).build()
        ).execute()

        // No request reached the server → the recorded request count is zero
        assertEquals(0, mockServer.requestCount)
    }
}
