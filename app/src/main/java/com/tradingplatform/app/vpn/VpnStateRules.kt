package com.tradingplatform.app.vpn

/**
 * État VPN perçu par l'app entière : `Connected` si le tunnel intégré est monté OU si un VPN
 * système tiers (app WireGuard officielle, OpenVPN…) l'est ; sinon l'état du tunnel intégré.
 * Sert la bannière globale (`AppNavViewModel`) et l'écran de connexion.
 */
fun computeEffectiveVpnState(inApp: VpnState, systemVpnActive: Boolean): VpnState = when {
    inApp is VpnState.Connected -> inApp
    systemVpnActive -> VpnState.Connected(serverIp = "")
    else -> inApp // Disconnected / Connecting / ConsentRequired / Error — le flux intégré décide
}

/**
 * État affiché par l'écran VPN et l'écran de connexion : distingue un VPN système tiers
 * ([VpnState.SystemVpnActive]) du tunnel intégré. `Connecting` prime sur le VPN système : pendant
 * l'établissement, le moniteur voit déjà le réseau `TRANSPORT_VPN` de notre propre tunnel.
 */
fun displayedVpnState(inApp: VpnState, systemVpnActive: Boolean): VpnState = when {
    inApp is VpnState.Connected || inApp is VpnState.Connecting -> inApp
    systemVpnActive -> VpnState.SystemVpnActive
    else -> inApp
}
