package com.tradingplatform.app.domain.model

/**
 * Server response to a successful `/v1/me/mobile-provisioning/{id}/register`.
 *
 * The phone uses these fields to assemble its local WireGuard config and bring
 * the tunnel up. The server's view of the peer is identified by [vpnPeerId];
 * the rest is the peer-side network shape.
 */
data class MobileProvisioningResult(
    val vpnPeerId: Long,
    val tunnelIp: String,
    val dns: String,
    val allowedIps: String,
    val serverPubkey: String,
    val endpoint: String,
)
