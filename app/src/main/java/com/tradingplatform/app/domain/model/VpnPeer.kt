package com.tradingplatform.app.domain.model

import java.time.Instant

data class VpnPeer(
    val id: String,
    val userId: Int,
    val label: String,
    val peerType: VpnPeerType,
    val wgTunnelIp: String,
    val isActive: Boolean,
    val pairedAt: Instant,
    val lastHandshake: Instant?,
    /** Carte appairée à ce peer (Radxa) ; null pour un téléphone / un poste. */
    val deviceId: String? = null,
    /** Vivacité côté serveur (heartbeats) ; null si le backend ne la fournit pas. */
    val isOnline: Boolean? = null,
    val lastSeenAt: Instant? = null,
) {
    /** Dernier signe de vie connu : heartbeat de la carte, sinon handshake WireGuard. */
    val lastActivity: Instant?
        get() = listOfNotNull(lastSeenAt, lastHandshake).maxOrNull()
}

enum class VpnPeerType {
    WEB_CLIENT,
    ANDROID_APP,
    RADXA_BOARD,
}
