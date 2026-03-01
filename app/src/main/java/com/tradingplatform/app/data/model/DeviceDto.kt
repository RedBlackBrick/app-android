package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class DeviceDto(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String,
    @Json(name = "status") val status: String,
    @Json(name = "wg_ip") val wgIp: String,
    @Json(name = "last_heartbeat") val lastHeartbeat: String,
)
