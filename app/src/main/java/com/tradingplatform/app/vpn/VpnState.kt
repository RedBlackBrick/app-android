package com.tradingplatform.app.vpn

/**
 * États possibles du tunnel WireGuard.
 * Sealed class (pas enum) — Error et Connected peuvent porter des données.
 */
sealed class VpnState {
    data object Disconnected : VpnState()
    data object Connecting : VpnState()
    data class Connected(val serverIp: String = "") : VpnState()

    /**
     * Un VPN système monté par une AUTRE app (WireGuard officielle, OpenVPN, WARP…) est actif
     * alors que le tunnel WireGuard intégré ne l'est pas (décision D6, commit 7da1974).
     *
     * Jamais émis par [WireGuardManager] (qui ne connaît que le tunnel intégré) : il est dérivé
     * par les consommateurs qui combinent [WireGuardManager.state] et [SystemVpnMonitor.active].
     * Politique réseau : autorisé au même titre que [Connected] (cf. `VpnRequiredInterceptor`,
     * qui consulte directement [SystemVpnMonitor]).
     */
    data object SystemVpnActive : VpnState()

    data class Error(val message: String) : VpnState()
}
