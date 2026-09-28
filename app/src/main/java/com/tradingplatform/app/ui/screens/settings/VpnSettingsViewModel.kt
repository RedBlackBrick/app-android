package com.tradingplatform.app.ui.screens.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.vpn.SystemVpnMonitor
import com.tradingplatform.app.vpn.VpnState
import com.tradingplatform.app.vpn.WireGuardConfig
import com.tradingplatform.app.vpn.WireGuardManager
import com.tradingplatform.app.vpn.WireGuardPeer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * VPN consent dialog state for [VpnSettingsScreen] (`VpnService.prepare`), meaningful only while
 * [VpnSettingsViewModel.vpnState] is [VpnState.ConsentRequired].
 * - [None]     — nothing to launch
 * - [Required] — the screen must launch the system dialog now
 * - [Launched] — dialog on screen, waiting for [VpnSettingsViewModel.onVpnConsentResult]
 *                (survives a config change: no second launch)
 * - [Denied]   — the user refused: explicit message + retry button
 */
sealed interface VpnConsentUiState {
    data object None : VpnConsentUiState
    data object Required : VpnConsentUiState
    data object Launched : VpnConsentUiState
    data object Denied : VpnConsentUiState
}

/**
 * ViewModel for VpnSettingsScreen.
 *
 * Exposes the immutable [vpnState] from [WireGuardManager].
 * [connect] reads the WireGuard config from [EncryptedDataStore] before starting the tunnel.
 * [disconnect] tears down the tunnel.
 *
 * Security invariant: the private key is read from EncryptedDataStore and passed directly
 * to WireGuardManager — it is never stored in the ViewModel or exposed to the UI.
 */
@HiltViewModel
class VpnSettingsViewModel @Inject constructor(
    private val wireGuardManager: WireGuardManager,
    private val dataStore: EncryptedDataStore,
    systemVpnMonitor: SystemVpnMonitor,
) : ViewModel() {

    /**
     * Etat affiché du VPN :
     * - tunnel intégré up → [VpnState.Connected] ;
     * - sinon, VPN système tiers actif (app WireGuard externe, OpenVPN…) →
     *   [VpnState.SystemVpnActive] (décision D6) — distinct de Connected pour ne pas afficher
     *   « Tunnel WireGuard actif » quand c'est un autre VPN qui porte le trafic ;
     * - sinon l'état du tunnel intégré (Disconnected / Connecting / Error).
     *
     * Connecting prime sur sysActive : pendant l'établissement, [SystemVpnMonitor] voit déjà
     * le réseau TRANSPORT_VPN du GoBackend (notre propre tunnel) avant le callback UP ; sans
     * cette priorité l'écran afficherait brièvement « VPN système actif » à chaque connexion.
     */
    val vpnState: StateFlow<VpnState> =
        combine(wireGuardManager.state, systemVpnMonitor.active) { inApp, sysActive ->
            when {
                inApp is VpnState.Connected || inApp is VpnState.Connecting -> inApp
                sysActive -> VpnState.SystemVpnActive
                else -> inApp
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = wireGuardManager.state.value,
        )

    private val _consentState = MutableStateFlow<VpnConsentUiState>(VpnConsentUiState.None)

    /** See [VpnConsentUiState]. Derived from the DISPLAYED [vpnState]: when a system VPN is
     *  active the screen shows SystemVpnActive and never pops the consent dialog (accepting it
     *  would revoke the other VPN app). */
    val consentState: StateFlow<VpnConsentUiState> = _consentState.asStateFlow()

    init {
        viewModelScope.launch {
            vpnState.collect { state ->
                _consentState.update { current ->
                    when {
                        // Entering ConsentRequired (connect()/reconnect() from anywhere): ask the
                        // screen to launch the dialog, unless it is already up or was refused.
                        state is VpnState.ConsentRequired ->
                            if (current == VpnConsentUiState.None) VpnConsentUiState.Required else current
                        current == VpnConsentUiState.Launched -> current // result still pending
                        else -> VpnConsentUiState.None
                    }
                }
            }
        }
    }

    /**
     * Initiates the WireGuard tunnel.
     * Reads the WireGuard config from EncryptedDataStore ([DataStoreKeys.WG_CONFIG] JSON).
     * When that JSON is absent — the mobile-provisioning flow only persists the individual
     * `wg_*` keys — falls back to [WireGuardManager.reconnect], which rebuilds the config from
     * them (and itself no-ops with a warning when they are missing too).
     */
    fun connect() {
        viewModelScope.launch {
            val config = loadWireGuardConfig()
            if (config == null) {
                Timber.w("VpnSettingsViewModel.connect: no WG_CONFIG JSON — reconnect from wg_* keys")
                wireGuardManager.reconnect()
                return@launch
            }
            wireGuardManager.connect(config)
        }
    }

    /** Intent of the system VPN consent dialog, or null when consent is already granted. */
    fun vpnConsentIntent(): Intent? = wireGuardManager.prepareIntent()

    /** The screen launched the consent dialog. */
    fun onVpnConsentLaunched() {
        _consentState.update { if (it == VpnConsentUiState.Required) VpnConsentUiState.Launched else it }
    }

    /**
     * Result of the system VPN consent dialog (RESULT_OK → [granted]).
     * Granted → the connect that stopped on [VpnState.ConsentRequired] is replayed.
     * Denied → [VpnConsentUiState.Denied]: explicit message + retry button.
     */
    fun onVpnConsentResult(granted: Boolean) {
        val current = _consentState.value
        if (current != VpnConsentUiState.Required && current != VpnConsentUiState.Launched) return
        if (granted) {
            _consentState.value = VpnConsentUiState.None
            wireGuardManager.retryAfterConsent()
        } else {
            Timber.w("VpnSettingsViewModel: VPN consent denied by the user")
            _consentState.value = VpnConsentUiState.Denied
        }
    }

    /** "Autoriser le VPN" / "Réessayer": asks the screen to launch the consent dialog (again). */
    fun requestVpnConsent() {
        if (vpnState.value !is VpnState.ConsentRequired) return
        _consentState.update {
            if (it == VpnConsentUiState.None || it == VpnConsentUiState.Denied) VpnConsentUiState.Required else it
        }
    }

    /** Tears down the WireGuard tunnel. Safe to call when already disconnected. */
    fun disconnect() {
        viewModelScope.launch {
            wireGuardManager.disconnect()
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Loads the WireGuard config from EncryptedDataStore.
     * The stored JSON key [DataStoreKeys.WG_CONFIG] is expected to encode all fields needed
     * to build a [WireGuardConfig]. Returns null if the config is absent or incomplete.
     *
     * Note: the private key is read here and never logged (CLAUDE.md §1).
     */
    private suspend fun loadWireGuardConfig(): WireGuardConfig? {
        val privateKey = dataStore.readString(DataStoreKeys.WG_PRIVATE_KEY) ?: return null
        val configJson = dataStore.readString(DataStoreKeys.WG_CONFIG) ?: return null

        return try {
            // Parse the JSON config stored during pairing.
            // Expected format (stored by PairingRepositoryImpl after a successful pairing):
            // {
            //   "address": "10.42.0.x/24",
            //   "dns": "1.1.1.1",
            //   "peer_public_key": "<base64>",
            //   "peer_endpoint": "vps.example.com:51820",
            //   "peer_allowed_ips": "0.0.0.0/0, ::/0",
            //   "peer_keepalive": 25
            // }
            val address = extractJsonString(configJson, "address") ?: return null
            val dns = extractJsonString(configJson, "dns") ?: "1.1.1.1"
            val peerPublicKey = extractJsonString(configJson, "peer_public_key") ?: return null
            val peerEndpoint = extractJsonString(configJson, "peer_endpoint") ?: return null
            val peerAllowedIPs = extractJsonString(configJson, "peer_allowed_ips") ?: "0.0.0.0/0, ::/0"
            val peerKeepalive = extractJsonInt(configJson, "peer_keepalive") ?: 25

            WireGuardConfig(
                privateKey = privateKey,
                address = address,
                dns = dns,
                peer = WireGuardPeer(
                    publicKey = peerPublicKey,
                    endpoint = peerEndpoint,
                    allowedIPs = peerAllowedIPs,
                    persistentKeepalive = peerKeepalive,
                ),
            )
        } catch (e: Exception) {
            Timber.e(e, "VpnSettingsViewModel: failed to parse WireGuard config — returning null")
            null
        }
    }

    /** Minimal JSON string extractor — avoids adding a Moshi dependency to the ViewModel layer. */
    private fun extractJsonString(json: String, key: String): String? {
        val pattern = Regex(""""$key"\s*:\s*"([^"]*?)"""")
        return pattern.find(json)?.groupValues?.getOrNull(1)
    }

    /** Minimal JSON int extractor for numeric fields. */
    private fun extractJsonInt(json: String, key: String): Int? {
        val pattern = Regex(""""$key"\s*:\s*(\d+)""")
        return pattern.find(json)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
}
