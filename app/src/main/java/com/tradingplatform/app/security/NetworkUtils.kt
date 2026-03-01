package com.tradingplatform.app.security

import android.util.Patterns
import java.net.InetAddress

/**
 * Valide qu'une adresse IP est sur un réseau local (RFC-1918).
 *
 * Rejette tout ce qui n'est pas une IP littérale — InetAddress.getByName() sur une IP string
 * ne fait pas de DNS lookup ; le guard Patterns.IP_ADDRESS bloque les hostnames (DNS rebinding).
 *
 * @param ip Adresse IP à valider (doit être une IP littérale, pas un hostname)
 * @return true si l'IP est locale (site-local, link-local ou loopback)
 */
fun isLocalNetwork(ip: String): Boolean {
    if (!Patterns.IP_ADDRESS.matcher(ip).matches()) return false
    return try {
        val addr = InetAddress.getByName(ip)
        addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isLoopbackAddress
    } catch (e: Exception) {
        false
    }
}
