package com.tradingplatform.app.security

import java.net.InetAddress

private val IPV4_REGEX = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

/**
 * Valide qu'une adresse IP est sur un réseau local (RFC-1918).
 *
 * Rejette tout ce qui n'est pas une IP littérale — InetAddress.getByName() sur une IP string
 * ne fait pas de DNS lookup ; le guard IPV4_REGEX bloque les hostnames (DNS rebinding).
 *
 * @param ip Adresse IP à valider (doit être une IP littérale, pas un hostname)
 * @return true si l'IP est locale (site-local, link-local ou loopback)
 */
fun isLocalNetwork(ip: String): Boolean {
    val match = IPV4_REGEX.matchEntire(ip) ?: return false
    if (match.groupValues.drop(1).any { it.toInt() > 255 }) return false
    return try {
        val addr = InetAddress.getByName(ip)
        addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isLoopbackAddress
    } catch (e: Exception) {
        false
    }
}
