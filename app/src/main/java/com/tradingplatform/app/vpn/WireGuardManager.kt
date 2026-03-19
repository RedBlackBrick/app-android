package com.tradingplatform.app.vpn

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.domain.model.SetupQrData
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Public API for the WireGuard VPN tunnel.
 *
 * All callers must check [state] before making network requests.
 * The private key never leaves this class — it is never logged.
 *
 * Security invariants:
 * - Private key is read from EncryptedDataStore, never hardcoded or logged.
 * - [state] is an immutable [StateFlow] — callers cannot mutate it.
 * - All coroutine work runs on Dispatchers.IO via [applicationScope].
 */
@Singleton
class WireGuardManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val applicationScope: CoroutineScope,
    private val dataStore: EncryptedDataStore,
) {
    private val _state = MutableStateFlow<VpnState>(VpnState.Disconnected)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private var backend: Backend? = null
    private var currentTunnel: Tunnel? = null

    companion object {
        private const val TAG = "WireGuardManager"
        private const val TUNNEL_NAME = "trading_platform"
    }

    /**
     * Connects the WireGuard tunnel using the provided [config].
     *
     * The private key in [config] comes from EncryptedDataStore and must never be logged.
     * Starts [WireGuardVpnService] as a foreground service first to satisfy Android 14+
     * foreground service requirements before binding the backend.
     */
    fun connect(config: WireGuardConfig) {
        applicationScope.launch(Dispatchers.IO) {
            try {
                _state.value = VpnState.Connecting

                // Start the foreground service before initiating the tunnel.
                // Required on Android 14+ (API 34) to keep the VPN alive in background.
                val connectIntent = Intent(context, WireGuardVpnService::class.java).apply {
                    action = WireGuardVpnService.ACTION_CONNECT
                }
                ContextCompat.startForegroundService(context, connectIntent)

                // Initialise the GoBackend (BoringTun userspace implementation) once.
                if (backend == null) {
                    backend = GoBackend(context)
                }

                val tunnel = buildTunnel()
                val wgConfig = buildWireGuardLibConfig(config)

                backend?.setState(tunnel, Tunnel.State.UP, wgConfig)
                currentTunnel = tunnel

                // _state transition to Connected is handled exclusively by onStateChange
                // (Tunnel.State.UP → VpnState.Connected). Setting it here would race with
                // the callback and could mark the tunnel Connected before it is actually UP.
                Timber.tag(TAG).i("WireGuard tunnel UP requested — tunnel=$TUNNEL_NAME")

            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "WireGuard connect error")
                _state.value = VpnState.Error(e.message ?: "Connection failed")
            }
        }
    }

    /**
     * Configure et connecte le tunnel à partir des données du QR d'onboarding.
     * Stocke la config dans EncryptedDataStore avant de connecter.
     *
     * La clé privée WireGuard ne doit jamais être loggée — [REDACTED] (CLAUDE.md §1).
     */
    suspend fun configureFromSetupQr(data: SetupQrData) {
        withContext(Dispatchers.IO) {
            dataStore.writeString(DataStoreKeys.WG_PRIVATE_KEY, data.wgPrivateKey)
            dataStore.writeString(DataStoreKeys.WG_ENDPOINT, data.endpoint)
            dataStore.writeString(DataStoreKeys.WG_SERVER_PUBKEY, data.wgPublicKeyServer)
            dataStore.writeString(DataStoreKeys.WG_TUNNEL_IP, data.tunnelIp)
            dataStore.writeString(DataStoreKeys.WG_DNS, data.dns)

            val config = WireGuardConfig(
                privateKey = data.wgPrivateKey,
                address = data.tunnelIp,
                dns = data.dns,
                peer = WireGuardPeer(
                    publicKey = data.wgPublicKeyServer,
                    endpoint = data.endpoint,
                ),
            )
            connect(config)
        }
    }

    /**
     * Disconnects the WireGuard tunnel and stops the foreground service.
     * Safe to call when already disconnected — no-op in that case.
     */
    fun disconnect() {
        applicationScope.launch(Dispatchers.IO) {
            try {
                val tunnel = currentTunnel
                if (tunnel != null) {
                    backend?.setState(tunnel, Tunnel.State.DOWN, null)
                    currentTunnel = null
                }
                _state.value = VpnState.Disconnected
                Timber.tag(TAG).i("WireGuard tunnel disconnected")

                // Stop the foreground notification.
                val disconnectIntent = Intent(context, WireGuardVpnService::class.java).apply {
                    action = WireGuardVpnService.ACTION_DISCONNECT
                }
                context.startService(disconnectIntent)

            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "WireGuard disconnect error")
                _state.value = VpnState.Error(e.message ?: "Disconnect failed")
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Builds a [Tunnel] that forwards state changes to [_state].
     */
    private fun buildTunnel(): Tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME

        override fun onStateChange(newState: Tunnel.State) {
            _state.value = when (newState) {
                Tunnel.State.UP -> VpnState.Connected()
                Tunnel.State.DOWN -> VpnState.Disconnected
                Tunnel.State.TOGGLE -> VpnState.Connecting
            }
            Timber.tag(TAG).d("WireGuard tunnel state changed: $newState")
        }
    }

    /**
     * Converts our [WireGuardConfig] domain model into a [com.wireguard.config.Config]
     * expected by the wireguard-android backend.
     *
     * IMPORTANT: [WireGuardConfig.privateKey] is never logged here (§ CLAUDE.md §1).
     */
    private fun buildWireGuardLibConfig(config: WireGuardConfig): com.wireguard.config.Config {
        val interfaceBuilder = com.wireguard.config.Interface.Builder()
            .parsePrivateKey(config.privateKey)   // private key — never log
            .parseAddresses(config.address)
            .parseDnsServers(config.dns)

        val peerBuilder = com.wireguard.config.Peer.Builder()
            .parsePublicKey(config.peer.publicKey)
            .parseAllowedIPs(config.peer.allowedIPs)
            .parseEndpoint(config.peer.endpoint)
            .apply {
                if (config.peer.persistentKeepalive > 0) {
                    parsePersistentKeepalive(config.peer.persistentKeepalive.toString())
                }
                config.peer.presharedKey?.let { parsePreSharedKey(it) }
            }

        return com.wireguard.config.Config.Builder()
            .setInterface(interfaceBuilder.build())
            .addPeer(peerBuilder.build())
            .build()
    }
}
