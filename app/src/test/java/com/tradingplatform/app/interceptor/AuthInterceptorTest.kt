package com.tradingplatform.app.interceptor

import com.tradingplatform.app.data.api.interceptor.AuthInterceptor
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import io.mockk.coEvery
import io.mockk.mockk
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

class AuthInterceptorTest {
    private val mockServer = MockWebServer()
    private val dataStore = mockk<EncryptedDataStore>()

    @Before
    fun setUp() {
        mockServer.start()
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun `adds Authorization header when token available`() = runTest {
        coEvery { dataStore.readString(DataStoreKeys.ACCESS_TOKEN) } returns "test_token_123"

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(dataStore))
            .build()

        mockServer.enqueue(MockResponse().setResponseCode(200))

        client.newCall(
            Request.Builder().url(mockServer.url("/test")).build()
        ).execute()

        val recordedRequest = mockServer.takeRequest()
        assertEquals("Bearer test_token_123", recordedRequest.getHeader("Authorization"))
        assertNotNull(recordedRequest.getHeader("X-App-Version"))
    }

    @Test
    fun `adds X-App-Version without Authorization when no token`() = runTest {
        coEvery { dataStore.readString(DataStoreKeys.ACCESS_TOKEN) } returns null

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(dataStore))
            .build()

        mockServer.enqueue(MockResponse().setResponseCode(200))

        client.newCall(
            Request.Builder().url(mockServer.url("/test")).build()
        ).execute()

        val recordedRequest = mockServer.takeRequest()
        assert(recordedRequest.getHeader("Authorization") == null)
        assertNotNull(recordedRequest.getHeader("X-App-Version"))
    }
}
