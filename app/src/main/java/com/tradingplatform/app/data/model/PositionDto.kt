package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.math.BigDecimal

@JsonClass(generateAdapter = true)
data class PositionDto(
    @Json(name = "id") val id: Int,
    @Json(name = "symbol") val symbol: String,
    @Json(name = "quantity") val quantity: BigDecimal,
    @Json(name = "avg_price") val avgPrice: BigDecimal,
    @Json(name = "current_price") val currentPrice: BigDecimal,
    @Json(name = "unrealized_pnl") val unrealizedPnl: BigDecimal,
    @Json(name = "unrealized_pnl_percent") val unrealizedPnlPercent: Double,
    @Json(name = "status") val status: String,
    @Json(name = "opened_at") val openedAt: String,
)
