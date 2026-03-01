package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class DeviceListResponseDto(
    @Json(name = "devices") val devices: List<DeviceDto>,
)
