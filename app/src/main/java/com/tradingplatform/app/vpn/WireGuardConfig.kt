package com.tradingplatform.app.vpn

/**
 * Configuration d'un tunnel WireGuard.
 * Modèle de données pur — pas de logique réseau.
 */
data class WireGuardConfig(
    val privateKey: String,
    val address: String,
    val dns: String = "1.1.1.1",
    val peer: WireGuardPeer,
) {
    /**
     * Génère le fichier de configuration WireGuard au format INI.
     */
    fun toConfigString(): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = $privateKey")
        appendLine("Address = $address")
        appendLine("DNS = $dns")
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = ${peer.publicKey}")
        if (peer.presharedKey != null) appendLine("PresharedKey = ${peer.presharedKey}")
        appendLine("AllowedIPs = ${peer.allowedIPs}")
        appendLine("Endpoint = ${peer.endpoint}")
        if (peer.persistentKeepalive > 0) appendLine("PersistentKeepalive = ${peer.persistentKeepalive}")
    }
}

data class WireGuardPeer(
    val publicKey: String,
    val presharedKey: String? = null,
    val allowedIPs: String = DEFAULT_ALLOWED_IPS,
    val endpoint: String,
    val persistentKeepalive: Int = 25,
) {
    companion object {
        /**
         * Full-tunnel routes. Last-resort fallback only: the routes the server provisioned
         * (`allowed_ips` of `/register`) are persisted under `DataStoreKeys.WG_ALLOWED_IPS`
         * and take precedence whenever the config is rebuilt from the store.
         */
        const val DEFAULT_ALLOWED_IPS = "0.0.0.0/0, ::/0"
    }
}
