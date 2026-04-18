package com.tradingplatform.app.interceptor

import com.tradingplatform.app.data.api.interceptor.VpnRequiredInterceptor
import com.tradingplatform.app.vpn.VpnNotConnectedException
import com.tradingplatform.app.vpn.VpnState
import com.tradingplatform.app.vpn.WireGuardManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class VpnRequiredInterceptorTest {
    private val mockServer = MockWebServer()
    private val vpnManager = mockk<WireGuardManager>()
    private val vpnState = MutableStateFlow<VpnState>(VpnState.Disconnected)
    private val systemVpnMonitor = mockk<com.tradingplatform.app.vpn.SystemVpnMonitor>(relaxed = true).also {
        every { it.active } returns MutableStateFlow(false)
    }

    @Before
    fun setUp() {
        mockServer.start()
        every { vpnManager.state } returns vpnState
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    private fun buildInterceptor(): VpnRequiredInterceptor {
        return VpnRequiredInterceptor(vpnManager, systemVpnMonitor)
    }

    @Test
    fun `throws VpnNotConnectedException when VPN disconnected`() {
        vpnState.value = VpnState.Disconnected

        val client = OkHttpClient.Builder()
            .addInterceptor(buildInterceptor())
            .build()

        mockServer.enqueue(MockResponse().setResponseCode(200))

        assertThrows(VpnNotConnectedException::class.java) {
            client.newCall(
                Request.Builder().url(mockServer.url("/test")).build()
            ).execute()
        }
    }

    @Test
    fun `proceeds normally when VPN connected`() {
        vpnState.value = VpnState.Connected()

        val client = OkHttpClient.Builder()
            .addInterceptor(buildInterceptor())
            .build()

        mockServer.enqueue(MockResponse().setResponseCode(200))

        val response = client.newCall(
            Request.Builder().url(mockServer.url("/test")).build()
        ).execute()

        assert(response.code == 200)
    }
}
