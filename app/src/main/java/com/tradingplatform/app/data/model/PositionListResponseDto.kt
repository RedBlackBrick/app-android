package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PositionListResponseDto(
    @Json(name = "positions") val positions: List<PositionDto>,
    @Json(name = "total") val total: Int,
)
