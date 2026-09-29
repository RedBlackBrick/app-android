package com.tradingplatform.app.ui.screens.settings

import android.content.Intent
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.ui.screens.setup.VPN_CONSENT_DENIED_MESSAGE
import com.tradingplatform.app.util.MainDispatcherRule
import com.tradingplatform.app.vpn.SystemVpnMonitor
import com.tradingplatform.app.vpn.displayedVpnState
import com.tradingplatform.app.vpn.VpnState
import com.tradingplatform.app.vpn.WireGuardConfig
import com.tradingplatform.app.vpn.WireGuardManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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

    // ── System VPN (decision D6) ──────────────────────────────────────────────

    @Test
    fun `vpnState is SystemVpnActive when a system VPN is up and in-app tunnel is Disconnected`() = runTest {
        every { wireGuardManager.state } returns MutableStateFlow<VpnState>(VpnState.Disconnected)
        every { systemVpnMonitor.active } returns MutableStateFlow(true)

        val viewModel = createViewModel()

        assertEquals(VpnState.SystemVpnActive, viewModel.vpnState.value)
    }

    @Test
    fun `initial vpnState asks Android when the monitor callback has not reported the system VPN yet`() = runTest {
        every { wireGuardManager.state } returns MutableStateFlow<VpnState>(VpnState.Disconnected)
        // Comme le vrai moniteur : `isActiveNow()` met aussi à jour le flux `active`.
        val sysActive = MutableStateFlow(false)
        every { systemVpnMonitor.active } returns sysActive
        every { systemVpnMonitor.isActiveNow() } answers { sysActive.value = true; true }

        val viewModel = createViewModel()

        assertEquals(VpnState.SystemVpnActive, viewModel.vpnState.value)
    }

    @Test
    fun `displayedVpnState rules - Connecting wins over a system VPN, Disconnected without any`() {
        assertEquals(VpnState.Connecting, displayedVpnState(VpnState.Connecting, systemVpnActive = true))
        assertEquals(VpnState.SystemVpnActive, displayedVpnState(VpnState.Disconnected, systemVpnActive = true))
        assertEquals(VpnState.Disconnected, displayedVpnState(VpnState.Disconnected, systemVpnActive = false))
    }

    @Test
    fun `vpnState prefers in-app Connected over system VPN`() = runTest {
        every { wireGuardManager.state } returns MutableStateFlow<VpnState>(VpnState.Connected())
        every { systemVpnMonitor.active } returns MutableStateFlow(true)

        val viewModel = createViewModel()

        assertEquals(VpnState.Connected(), viewModel.vpnState.value)
    }

    @Test
    fun `vpnState keeps Connecting while own tunnel establishes even if a VPN network is visible`() = runTest {
        every { wireGuardManager.state } returns MutableStateFlow<VpnState>(VpnState.Connecting)
        every { systemVpnMonitor.active } returns MutableStateFlow(true)

        val viewModel = createViewModel()

        assertEquals(VpnState.Connecting, viewModel.vpnState.value)
    }

    @Test
    fun `vpnState switches back to in-app state when the system VPN goes away`() = runTest {
        every { wireGuardManager.state } returns MutableStateFlow<VpnState>(VpnState.Disconnected)
        val sysActive = MutableStateFlow(true)
        every { systemVpnMonitor.active } returns sysActive

        val viewModel = createViewModel()
        assertEquals(VpnState.SystemVpnActive, viewModel.vpnState.value)

        sysActive.value = false

        assertEquals(VpnState.Disconnected, viewModel.vpnState.value)
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
    fun `connect falls back to reconnect from wg keys when config JSON is missing`() = runTest {
        // Mobile provisioning only persists the individual wg_* keys, never WG_CONFIG.
        coEvery { dataStore.readString(DataStoreKeys.WG_CONFIG) } returns null
        every { wireGuardManager.state } returns MutableStateFlow(VpnState.Disconnected)
        val viewModel = createViewModel()

        viewModel.connect()

        coVerify(exactly = 0) { wireGuardManager.connect(any()) }
        verify(exactly = 1) { wireGuardManager.reconnect() }
    }

    @Test
    fun `connect uses persisted provisioned allowed IPs when the config JSON has none`() = runTest {
        coEvery { dataStore.readString(DataStoreKeys.WG_CONFIG) } returns
            validConfigJson.replace(Regex("""\s*"peer_allowed_ips": "[^"]*","""), "")
        coEvery { dataStore.readString(DataStoreKeys.WG_ALLOWED_IPS) } returns "10.42.0.0/24"
        every { wireGuardManager.state } returns MutableStateFlow(VpnState.Disconnected)
        val config = slot<WireGuardConfig>()
        every { wireGuardManager.connect(capture(config)) } returns Unit
        val viewModel = createViewModel()

        viewModel.connect()

        assertEquals("10.42.0.0/24", config.captured.peer.allowedIPs)
    }

    // ── VPN consent (VpnService.prepare) ──────────────────────────────────────

    @Test
    fun `ConsentRequired is displayed and asks the screen to launch the consent dialog`() = runTest {
        every { wireGuardManager.state } returns MutableStateFlow<VpnState>(VpnState.ConsentRequired)
        val consentIntent = mockk<Intent>(relaxed = true)
        every { wireGuardManager.prepareIntent() } returns consentIntent

        val viewModel = createViewModel()

        assertEquals(VpnState.ConsentRequired, viewModel.vpnState.value)
        assertEquals(VpnConsentUiState.Required, viewModel.consentState.value)
        assertSame(consentIntent, viewModel.vpnConsentIntent())
    }

    @Test
    fun `connect reaching ConsentRequired requests the consent dialog`() = runTest {
        val stateFlow = MutableStateFlow<VpnState>(VpnState.Disconnected)
        every { wireGuardManager.state } returns stateFlow
        every { wireGuardManager.connect(any()) } answers { stateFlow.value = VpnState.ConsentRequired }
        val viewModel = createViewModel()
        assertEquals(VpnConsentUiState.None, viewModel.consentState.value)

        viewModel.connect()

        assertEquals(VpnConsentUiState.Required, viewModel.consentState.value)
    }

    @Test
    fun `consent dialog is not requested while a system VPN is displayed`() = runTest {
        // Another VPN app took over (hence our consent was revoked): accepting the dialog would
        // tear the other VPN down — the screen shows SystemVpnActive and stays quiet.
        every { wireGuardManager.state } returns MutableStateFlow<VpnState>(VpnState.ConsentRequired)
        every { systemVpnMonitor.active } returns MutableStateFlow(true)

        val viewModel = createViewModel()

        assertEquals(VpnState.SystemVpnActive, viewModel.vpnState.value)
        assertEquals(VpnConsentUiState.None, viewModel.consentState.value)
    }

    @Test
    fun `consent granted retries the connection`() = runTest {
        val stateFlow = MutableStateFlow<VpnState>(VpnState.ConsentRequired)
        every { wireGuardManager.state } returns stateFlow
        every { wireGuardManager.retryAfterConsent() } answers { stateFlow.value = VpnState.Connecting }
        val viewModel = createViewModel()
        viewModel.onVpnConsentLaunched()
        assertEquals(VpnConsentUiState.Launched, viewModel.consentState.value)

        viewModel.onVpnConsentResult(granted = true)

        verify(exactly = 1) { wireGuardManager.retryAfterConsent() }
        assertEquals(VpnConsentUiState.None, viewModel.consentState.value)
        assertEquals(VpnState.Connecting, viewModel.vpnState.value)
    }

    @Test
    fun `consent denied shows the explicit error and retry re-requests the dialog`() = runTest {
        every { wireGuardManager.state } returns MutableStateFlow<VpnState>(VpnState.ConsentRequired)
        val viewModel = createViewModel()
        viewModel.onVpnConsentLaunched()

        viewModel.onVpnConsentResult(granted = false)

        assertEquals(VpnConsentUiState.Denied, viewModel.consentState.value)
        verify(exactly = 0) { wireGuardManager.retryAfterConsent() }
        assertEquals(
            "Autorisation VPN refusée — le tunnel est requis pour utiliser l'application",
            VPN_CONSENT_DENIED_MESSAGE,
        )

        viewModel.requestVpnConsent()

        assertEquals(VpnConsentUiState.Required, viewModel.consentState.value)
    }

    @Test
    fun `consent state resets when the tunnel leaves ConsentRequired`() = runTest {
        val stateFlow = MutableStateFlow<VpnState>(VpnState.ConsentRequired)
        every { wireGuardManager.state } returns stateFlow
        val viewModel = createViewModel()
        viewModel.onVpnConsentLaunched()
        viewModel.onVpnConsentResult(granted = false)
        assertEquals(VpnConsentUiState.Denied, viewModel.consentState.value)

        stateFlow.value = VpnState.Disconnected

        assertEquals(VpnConsentUiState.None, viewModel.consentState.value)
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
