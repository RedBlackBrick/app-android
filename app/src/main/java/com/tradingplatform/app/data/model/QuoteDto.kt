package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.math.BigDecimal

@JsonClass(generateAdapter = true)
data class QuoteDto(
    @Json(name = "symbol") val symbol: String,
    @Json(name = "price") val price: BigDecimal,
    @Json(name = "bid") val bid: BigDecimal? = null,
    @Json(name = "ask") val ask: BigDecimal? = null,
    @Json(name = "volume") val volume: Long,
    @Json(name = "change") val change: BigDecimal,
    @Json(name = "change_percent") val changePercent: Double,
    @Json(name = "timestamp") val timestamp: String,
    @Json(name = "source") val source: String,
)
