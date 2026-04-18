package com.tradingplatform.app.ui.screens.settings

import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.util.MainDispatcherRule
import com.tradingplatform.app.vpn.SystemVpnMonitor
import com.tradingplatform.app.vpn.VpnState
import com.tradingplatform.app.vpn.WireGuardManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VpnSettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val wireGuardManager = mockk<WireGuardManager>(relaxed = true)
    private val dataStore = mockk<EncryptedDataStore>()
    private val systemVpnMonitor = mockk<SystemVpnMonitor>(relaxed = true).apply {
        every { active } returns MutableStateFlow(false)
    }

    // Minimal valid WireGuard config JSON stored in datastore
    private val validConfigJson = """
        {
          "address": "10.42.0.5/24",
          "dns": "1.1.1.1",
          "peer_public_key": "dGVzdHB1YmxpY2tleWZvcndpcmVndWFyZA==",
          "peer_endpoint": "vps.example.com:51820",
          "peer_allowed_ips": "0.0.0.0/0, ::/0",
          "peer_keepalive": 25
        }
    """.trimIndent()

    @Before
    fun setUp() {
        coEvery { dataStore.readString(DataStoreKeys.WG_PRIVATE_KEY) } returns
            "cHJpdmF0ZWtleWZvcndpcmVndWFyZHRlc3Rpbmc="
        coEvery { dataStore.readString(DataStoreKeys.WG_CONFIG) } returns validConfigJson
    }

    private fun createViewModel(): VpnSettingsViewModel = VpnSettingsViewModel(
        wireGuardManager = wireGuardManager,
        dataStore = dataStore,
        systemVpnMonitor = systemVpnMonitor,
    )

    // ── vpnState reflects WireGuardManager state ──────────────────────────────

    @Test
    fun `vpnState reflects Disconnected state from WireGuardManager`() = runTest {
        val stateFlow = MutableStateFlow<VpnState>(VpnState.Disconnected)
        every { wireGuardManager.state } returns stateFlow

        val viewModel = createViewModel()

        assertEquals(VpnState.Disconnected, viewModel.vpnState.value)
    }

    @Test
    fun `vpnState reflects Connected state from WireGuardManager`() = runTest {
        val stateFlow = MutableStateFlow<VpnState>(VpnState.Connected())
        every { wireGuardManager.state } returns stateFlow

        val viewModel = createViewModel()

        assertEquals(VpnState.Connected(), viewModel.vpnState.value)
    }

    @Test
    fun `vpnState reflects Connecting state from WireGuardManager`() = runTest {
        val stateFlow = MutableStateFlow<VpnState>(VpnState.Connecting)
        every { wireGuardManager.state } returns stateFlow

        val viewModel = createViewModel()

        assertEquals(VpnState.Connecting, viewModel.vpnState.value)
    }

    @Test
    fun `vpnState reflects Error state from WireGuardManager`() = runTest {
        val stateFlow = MutableStateFlow<VpnState>(VpnState.Error("Connection failed"))
        every { wireGuardManager.state } returns stateFlow

        val viewModel = createViewModel()

        assertEquals(VpnState.Error("Connection failed"), viewModel.vpnState.value)
    }

    @Test
    fun `vpnState updates reactively when WireGuardManager state changes`() = runTest {
        val stateFlow = MutableStateFlow<VpnState>(VpnState.Disconnected)
        every { wireGuardManager.state } returns stateFlow

        val viewModel = createViewModel()

        assertEquals(VpnState.Disconnected, viewModel.vpnState.value)

        stateFlow.value = VpnState.Connected()

        assertEquals(VpnState.Connected(), viewModel.vpnState.value)
    }

    // ── connect() ─────────────────────────────────────────────────────────────

    @Test
    fun `connect calls wireGuardManager connect when config is present`() = runTest {
        every { wireGuardManager.state } returns MutableStateFlow(VpnState.Disconnected)
        val viewModel = createViewModel()

        viewModel.connect()

        coVerify(exactly = 1) { wireGuardManager.connect(any()) }
    }

    @Test
    fun `connect does not call wireGuardManager when private key is missing`() = runTest {
        coEvery { dataStore.readString(DataStoreKeys.WG_PRIVATE_KEY) } returns null
        every { wireGuardManager.state } returns MutableStateFlow(VpnState.Disconnected)
        val viewModel = createViewModel()

        viewModel.connect()

        coVerify(exactly = 0) { wireGuardManager.connect(any()) }
    }

    @Test
    fun `connect does not call wireGuardManager when config JSON is missing`() = runTest {
        coEvery { dataStore.readString(DataStoreKeys.WG_CONFIG) } returns null
        every { wireGuardManager.state } returns MutableStateFlow(VpnState.Disconnected)
        val viewModel = createViewModel()

        viewModel.connect()

        coVerify(exactly = 0) { wireGuardManager.connect(any()) }
    }

    // ── disconnect() ──────────────────────────────────────────────────────────

    @Test
    fun `disconnect calls wireGuardManager disconnect`() = runTest {
        every { wireGuardManager.state } returns MutableStateFlow(VpnState.Connected())
        val viewModel = createViewModel()

        viewModel.disconnect()

        coVerify(exactly = 1) { wireGuardManager.disconnect() }
    }

    @Test
    fun `disconnect is safe to call when already disconnected`() = runTest {
        every { wireGuardManager.state } returns MutableStateFlow(VpnState.Disconnected)
        val viewModel = createViewModel()

        viewModel.disconnect()

        // disconnect() should still delegate to WireGuardManager (which handles no-op internally)
        coVerify(exactly = 1) { wireGuardManager.disconnect() }
    }
}
