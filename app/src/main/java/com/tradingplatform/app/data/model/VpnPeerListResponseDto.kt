package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class VpnPeerListResponseDto(
    @Json(name = "peers") val peers: List<VpnPeerDto>,
    @Json(name = "total") val total: Int,
)
