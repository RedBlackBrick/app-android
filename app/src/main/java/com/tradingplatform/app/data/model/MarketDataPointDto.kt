package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.math.BigDecimal

@JsonClass(generateAdapter = true)
data class MarketDataPointDto(
    @Json(name = "timestamp") val timestamp: String,
    @Json(name = "open") val open: BigDecimal? = null,
    @Json(name = "high") val high: BigDecimal? = null,
    @Json(name = "low") val low: BigDecimal? = null,
    @Json(name = "close") val close: BigDecimal,
    @Json(name = "volume") val volume: Long? = null,
)
