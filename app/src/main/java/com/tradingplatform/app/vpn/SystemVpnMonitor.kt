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

    /**
     * Relit l'état réel auprès d'Android (sans dépendre des callbacks) et met [active] à jour.
     *
     * Filet de sécurité : les callbacks peuvent être en retard ou manquer un réseau (ex. VPN monté
     * pendant que le process était gelé, réseau sortant du filtre de la requête). Appelé par les
     * intercepteurs / le Worker quand [active] vaut `false`, et au retour au premier plan.
     * Ne consulte volontairement que les réseaux connus d'Android, jamais un état mémorisé.
     */
    fun isActiveNow(): Boolean {
        val found = currentVpnNetworks()
        synchronized(vpnNetworks) {
            // Les réseaux découverts ici sont ajoutés au même ensemble que ceux des callbacks :
            // leur `onLost` ultérieur repasse donc bien [active] à `false`.
            vpnNetworks.addAll(found)
            val now = vpnNetworks.isNotEmpty()
            if (now != _active.value) {
                _active.value = now
                Timber.tag(TAG).d("SystemVpnMonitor: refreshed from system, active=$now")
            }
            return now
        }
    }

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
                // `NetworkRequest.Builder()` impose par défaut NOT_RESTRICTED + TRUSTED (+ FOREGROUND
                // sur les Android récents) et NOT_VPN. Un VPN tiers qui n'a pas exactement ces
                // capacités — ou qui perd FOREGROUND quand aucune app ne l'utilise — sortait du
                // filtre : `onLost` → « VPN déconnecté » alors que le tunnel était bien monté.
                // On efface donc toutes les capacités par défaut et on ne filtre que sur le transport.
                val request = NetworkRequest.Builder()
                    .clearCapabilities()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
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

    private fun currentlyActive(): Boolean = currentVpnNetworks().isNotEmpty()

    @Suppress("DEPRECATION") // allNetworks : seul moyen de lire l'état courant de façon synchrone.
    private fun currentVpnNetworks(): List<Network> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return emptyList()
        return cm.allNetworks.filter { network ->
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    companion object {
        private const val TAG = "SystemVpnMonitor"
    }
}
