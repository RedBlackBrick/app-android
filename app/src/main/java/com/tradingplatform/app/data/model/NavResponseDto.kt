package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.math.BigDecimal

@JsonClass(generateAdapter = true)
data class NavResponseDto(
    @Json(name = "nav") val nav: BigDecimal,
    @Json(name = "cash") val cash: BigDecimal,
    @Json(name = "positions_value") val positionsValue: BigDecimal,
    @Json(name = "timestamp") val timestamp: String,
)
