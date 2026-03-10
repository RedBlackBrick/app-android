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
)

enum class VpnPeerType {
    WEB_CLIENT,
    ANDROID_APP,
    RADXA_BOARD,
}
