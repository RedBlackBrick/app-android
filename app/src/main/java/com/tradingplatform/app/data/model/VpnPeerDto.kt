package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class VpnPeerDto(
    @Json(name = "id") val id: String,
    @Json(name = "user_id") val userId: Int,
    @Json(name = "label") val label: String,
    @Json(name = "peer_type") val peerType: String,
    @Json(name = "wg_public_key") val wgPublicKey: String,
    @Json(name = "wg_tunnel_ip") val wgTunnelIp: String,
    @Json(name = "is_active") val isActive: Boolean,
    @Json(name = "paired_at") val pairedAt: String,
    @Json(name = "last_handshake") val lastHandshake: String?,
)
