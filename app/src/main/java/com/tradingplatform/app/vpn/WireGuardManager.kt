package com.tradingplatform.app.vpn

import android.content.Context
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.wireguard.android.backend.Tunnel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Public API for the in-app WireGuard VPN tunnel.
 *
 * All callers must check [state] before making network requests.
 * The private key never leaves this class — it is never logged.
 *
 * Security invariants:
 * - Private key is read from EncryptedDataStore, never hardcoded or logged.
 * - [state] is an immutable [StateFlow] — callers cannot mutate it.
 * - All tunnel work runs on [ioDispatcher] via [applicationScope].
 *
 * Concurrency (audit C-vpn-conc-1 / C-vpn-err-1):
 * - [connect] / [disconnect] are fire-and-forget but their bodies are serialised by [opMutex]:
 *   a disconnect requested while `setState(UP)` is running waits for it, then brings the
 *   tunnel DOWN — it is never silently lost.
 * - Last intent wins: every call takes a ticket from [requestSeq] synchronously; an operation
 *   that acquires the lock after a newer request was made is skipped (the newer one enforces
 *   the desired state). So `connect(); disconnect()` always ends Disconnected, whatever order
 *   the two coroutines reach the lock in.
 * - [backend] is created once, under the lock.
 * - A DOWN transition that we did not initiate (OS revocation, another VPN app taking over,
 *   `GoBackend$VpnService.onDestroy`) clears [currentTunnel] AND stops [WireGuardVpnService]
 *   so no stale "VPN connecté" notification survives.
 * - [VpnState.SystemVpnActive] is never emitted here: this class only knows the in-app tunnel.
 */
@Singleton
class WireGuardManager internal constructor(
    private val applicationScope: CoroutineScope,
    private val dataStore: EncryptedDataStore,
    private val backendFactory: () -> TunnelBackend,
    private val serviceController: VpnServiceController,
    private val ioDispatcher: CoroutineDispatcher,
) {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        applicationScope: CoroutineScope,
        dataStore: EncryptedDataStore,
    ) : this(
        applicationScope = applicationScope,
        dataStore = dataStore,
        backendFactory = { GoTunnelBackend(context) },
        serviceController = IntentVpnServiceController(context),
        ioDispatcher = Dispatchers.IO,
    )

    private val _state = MutableStateFlow<VpnState>(VpnState.Disconnected)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    /** Serialises the bodies of [connect] and [disconnect]. */
    private val opMutex = Mutex()

    /** Ticket counter — the highest ticket is the user's latest intent. */
    private val requestSeq = AtomicLong(0)

    /** Guarded by [opMutex]. */
    private var backend: TunnelBackend? = null

    /** Tunnel we brought UP (null when DOWN). Written under [opMutex] or by an external DOWN. */
    @Volatile
    private var currentTunnel: Tunnel? = null

    /**
     * True while [doConnect]/[doDisconnect] drive a transition: the DOWN callbacks they cause
     * (GoBackend swaps the old tunnel DOWN before UP, or our own DOWN) are handled by the
     * operation itself and must not stop the notification service behind its back.
     */
    @Volatile
    private var ownTransition = false

    /** Single tunnel identity for the whole process — GoBackend keys its state on it. */
    private val tunnel: Tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME

        override fun onStateChange(newState: Tunnel.State) {
            Timber.tag(TAG).d("WireGuard tunnel state changed: $newState (own=$ownTransition)")
            when (newState) {
                Tunnel.State.UP -> _state.value = VpnState.Connected()
                Tunnel.State.TOGGLE -> _state.value = VpnState.Connecting
                Tunnel.State.DOWN -> if (!ownTransition) onExternalDown()
            }
        }
    }

    companion object {
        private const val TAG = "WireGuardManager"
        private const val TUNNEL_NAME = "trading_platform"
    }

    /**
     * Reconnects the tunnel using the configuration stored in EncryptedDataStore.
     * Useful for manual retries from the UI when the tunnel is disconnected.
     * Does nothing if the private key or endpoint is missing from the store.
     */
    fun reconnect() {
        applicationScope.launch(ioDispatcher) {
            val privateKey = dataStore.readString(DataStoreKeys.WG_PRIVATE_KEY)
            val endpoint = dataStore.readString(DataStoreKeys.WG_ENDPOINT)
            val serverPubKey = dataStore.readString(DataStoreKeys.WG_SERVER_PUBKEY)
            val tunnelIp = dataStore.readString(DataStoreKeys.WG_TUNNEL_IP)
            val dns = dataStore.readString(DataStoreKeys.WG_DNS)

            if (privateKey != null && endpoint != null && serverPubKey != null && tunnelIp != null) {
                Timber.tag(TAG).i("WireGuard: manual reconnection requested")
                connect(
                    WireGuardConfig(
                        privateKey = privateKey,
                        address = tunnelIp,
                        dns = dns ?: "1.1.1.1",
                        peer = WireGuardPeer(
                            publicKey = serverPubKey,
                            endpoint = endpoint,
                        ),
                    )
                )
            } else {
                Timber.tag(TAG).w("WireGuard reconnect failed: missing configuration in DataStore")
            }
        }
    }

    /**
     * Connects the WireGuard tunnel using the provided [config].
     *
     * The private key in [config] comes from EncryptedDataStore and must never be logged.
     * Starts [WireGuardVpnService] as a foreground service first to satisfy Android 14+
     * foreground service requirements before bringing the tunnel UP.
     */
    fun connect(config: WireGuardConfig) {
        val ticket = requestSeq.incrementAndGet()
        applicationScope.launch(ioDispatcher) {
            opMutex.withLock { doConnect(config, ticket) }
        }
    }

    /**
     * Disconnects the WireGuard tunnel and stops the foreground service.
     * Safe to call when already disconnected, and while a [connect] is in flight: the
     * tunnel is brought DOWN once the pending `setState(UP)` returns.
     */
    fun disconnect() {
        val ticket = requestSeq.incrementAndGet()
        applicationScope.launch(ioDispatcher) {
            opMutex.withLock { doDisconnect(ticket) }
        }
    }

    // ── Operations (always called under opMutex) ─────────────────────────────

    private suspend fun doConnect(config: WireGuardConfig, ticket: Long) {
        if (isSuperseded(ticket)) {
            Timber.tag(TAG).d("WireGuard connect #$ticket superseded — skipped")
            return
        }
        _state.value = VpnState.Connecting
        ownTransition = true
        try {
            // Start the foreground service before initiating the tunnel.
            // Required on Android 14+ (API 34) to keep the VPN alive in background.
            serviceController.startForeground()

            val be = backend ?: backendFactory().also { backend = it }
            currentTunnel = tunnel
            val result = be.setState(tunnel, Tunnel.State.UP, config)

            if (result == Tunnel.State.UP) {
                // onStateChange(UP) normally already did this; GoBackend skips the callback when
                // the tunnel is already UP with an identical config, so set it explicitly.
                _state.value = VpnState.Connected()
                Timber.tag(TAG).i("WireGuard tunnel UP — tunnel=$TUNNEL_NAME")
            } else {
                Timber.tag(TAG).w("WireGuard setState(UP) returned $result")
                markDown(VpnState.Disconnected)
            }
            // A disconnect() issued while setState(UP) was blocking holds a newer ticket and is
            // queued on opMutex: it runs right after we release the lock and brings the tunnel
            // DOWN. Nothing to do here beyond logging.
            if (isSuperseded(ticket)) {
                Timber.tag(TAG).d("WireGuard connect #$ticket superseded while UP was in flight")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "WireGuard connect error")
            markDown(VpnState.Error(e.message ?: "Connection failed"))
        } finally {
            ownTransition = false
        }
    }

    private suspend fun doDisconnect(ticket: Long) {
        if (isSuperseded(ticket)) {
            Timber.tag(TAG).d("WireGuard disconnect #$ticket superseded — skipped")
            return
        }
        ownTransition = true
        try {
            val up = currentTunnel
            val be = backend
            if (up != null && be != null) {
                be.setState(up, Tunnel.State.DOWN, null)
            }
            markDown(VpnState.Disconnected)
            Timber.tag(TAG).i("WireGuard tunnel disconnected")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "WireGuard disconnect error")
            _state.value = VpnState.Error(e.message ?: "Disconnect failed")
        } finally {
            ownTransition = false
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun isSuperseded(ticket: Long): Boolean = requestSeq.get() != ticket

    /** Tunnel is DOWN: forget it, publish [newState] and remove the "VPN connecté" notification. */
    private fun markDown(newState: VpnState) {
        currentTunnel = null
        _state.value = newState
        stopNotificationService()
    }

    /**
     * DOWN we did not ask for: the OS revoked the VPN (user action in Settings, another VPN app
     * took over) and `GoBackend$VpnService.onDestroy` reported it. Called on the main thread.
     */
    private fun onExternalDown() {
        Timber.tag(TAG).i("WireGuard tunnel DOWN (external) — stopping notification service")
        markDown(VpnState.Disconnected)
    }

    private fun stopNotificationService() {
        try {
            serviceController.stop()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to stop WireGuardVpnService")
        }
    }
}
