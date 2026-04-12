package com.tradingplatform.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects a system-level VPN tunnel mounted by ANOTHER app (e.g. the official
 * WireGuard client, OpenVPN, Cloudflare WARP).
 *
 * [WireGuardManager] only tracks the in-app tunnel; without this monitor, users
 * who activate their VPN from the official WireGuard app see a permanent "VPN
 * déconnecté" banner and would have their requests blocked by
 * [com.tradingplatform.app.data.api.interceptor.VpnRequiredInterceptor] if the
 * latter did not also consult it.
 *
 * Uses a single [ConnectivityManager.NetworkCallback] registered on
 * construction.  The exposed [active] [StateFlow] is eventually-consistent: it
 * flips to `true` as soon as Android reports a network with
 * [NetworkCapabilities.TRANSPORT_VPN] and back to `false` when the last such
 * network is lost.
 *
 * Requires `android.permission.ACCESS_NETWORK_STATE`.
 */
@Singleton
class SystemVpnMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _active = MutableStateFlow(currentlyActive())
    val active: StateFlow<Boolean> = _active.asStateFlow()

    // Track every VPN-capable network currently known.  A single `Boolean` would
    // mis-report during transient states where two VPN networks overlap (very
    // rare but possible, e.g. during a tunnel switch).
    private val vpnNetworks = mutableSetOf<Network>()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            val hasVpn = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            synchronized(vpnNetworks) {
                val changed = if (hasVpn) vpnNetworks.add(network) else vpnNetworks.remove(network)
                if (changed) {
                    _active.value = vpnNetworks.isNotEmpty()
                    Timber.tag(TAG).d(
                        "SystemVpnMonitor: active=${_active.value} (tracked=${vpnNetworks.size})"
                    )
                }
            }
        }

        override fun onLost(network: Network) {
            synchronized(vpnNetworks) {
                if (vpnNetworks.remove(network)) {
                    _active.value = vpnNetworks.isNotEmpty()
                }
            }
        }
    }

    init {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    // Don't require NOT_VPN, don't require NET_CAPABILITY_INTERNET
                    // (some VPNs pass all traffic and don't advertise internet on
                    // the VPN network itself).
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build()
                cm.registerNetworkCallback(request, callback)
                Timber.tag(TAG).d("SystemVpnMonitor: callback registered")
            } else {
                Timber.tag(TAG).w("SystemVpnMonitor: ConnectivityManager unavailable")
            }
        } catch (e: SecurityException) {
            // Missing ACCESS_NETWORK_STATE permission — should not happen
            // given the manifest declares it, but degrade gracefully.
            Timber.tag(TAG).e(e, "SystemVpnMonitor: registration failed")
        }
    }

    private fun currentlyActive(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true
        }
        return false
    }

    companion object {
        private const val TAG = "SystemVpnMonitor"
    }
}
