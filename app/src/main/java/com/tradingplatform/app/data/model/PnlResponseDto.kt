package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.math.BigDecimal

@JsonClass(generateAdapter = true)
data class PnlResponseDto(
    @Json(name = "period") val period: String,
    @Json(name = "realized_pnl") val realizedPnl: BigDecimal,
    @Json(name = "unrealized_pnl") val unrealizedPnl: BigDecimal,
    @Json(name = "total_pnl") val totalPnl: BigDecimal,
    @Json(name = "total_pnl_percent") val totalPnlPercent: Double,
    @Json(name = "trades_count") val tradesCount: Int,
    @Json(name = "winning_trades") val winningTrades: Int,
    @Json(name = "losing_trades") val losingTrades: Int,
)
