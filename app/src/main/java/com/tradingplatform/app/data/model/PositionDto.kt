package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.math.BigDecimal

@JsonClass(generateAdapter = true)
data class PositionDto(
    @Json(name = "id") val id: Int,
    @Json(name = "symbol") val symbol: String,
    @Json(name = "quantity") val quantity: BigDecimal,
    @Json(name = "average_price") val avgPrice: BigDecimal,
    @Json(name = "last_price") val currentPrice: BigDecimal? = null,
    @Json(name = "unrealized_pnl") val unrealizedPnl: BigDecimal? = null,
    @Json(name = "unrealized_pnl_pct") val unrealizedPnlPercent: Double? = null,
    @Json(name = "is_active") val isActive: Boolean = true,
    @Json(name = "entry_timestamp") val openedAt: String? = null,
)
