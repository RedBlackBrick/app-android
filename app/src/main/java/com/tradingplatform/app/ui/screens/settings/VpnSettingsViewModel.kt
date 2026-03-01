package com.tradingplatform.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.vpn.VpnState
import com.tradingplatform.app.vpn.WireGuardConfig
import com.tradingplatform.app.vpn.WireGuardManager
import com.tradingplatform.app.vpn.WireGuardPeer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

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
) : ViewModel() {

    /** Immutable view of the VPN tunnel state — UI must not mutate this. */
    val vpnState: StateFlow<VpnState> = wireGuardManager.state

    /**
     * Initiates the WireGuard tunnel.
     * Reads the WireGuard config from EncryptedDataStore.
     * No-op if config is missing (logs a warning).
     */
    fun connect() {
        viewModelScope.launch {
            val config = loadWireGuardConfig()
            if (config == null) {
                Timber.w("VpnSettingsViewModel.connect: WireGuard config not found in datastore — aborting")
                return@launch
            }
            wireGuardManager.connect(config)
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
