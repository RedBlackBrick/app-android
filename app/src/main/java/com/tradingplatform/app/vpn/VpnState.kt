package com.tradingplatform.app.vpn

/**
 * États possibles du tunnel WireGuard.
 * Sealed class (pas enum) — Error et Connected peuvent porter des données.
 */
sealed class VpnState {
    data object Disconnected : VpnState()
    data object Connecting : VpnState()
    data class Connected(val serverIp: String = "") : VpnState()
    data class Error(val message: String) : VpnState()
}
