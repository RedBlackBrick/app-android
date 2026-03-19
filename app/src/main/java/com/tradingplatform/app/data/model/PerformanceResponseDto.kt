package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.math.BigDecimal

/**
 * DTO for `GET /v1/portfolios/{portfolio_id}/performance`
 * Maps to backend `PerformanceMetrics` schema.
 *
 * All fields are nullable — a brand-new portfolio with no trades has no
 * computable metrics.  Maps to domain model [com.tradingplatform.app.domain.model.PnlSummary].
 */
@JsonClass(generateAdapter = true)
data class PerformanceResponseDto(
    @Json(name = "total_return") val totalReturn: BigDecimal?,
    @Json(name = "total_return_pct") val totalReturnPct: Double?,
    @Json(name = "sharpe_ratio") val sharpeRatio: Double?,
    @Json(name = "sortino_ratio") val sortinoRatio: Double?,
    @Json(name = "max_drawdown") val maxDrawdown: Double?,
    @Json(name = "volatility") val volatility: Double?,
    @Json(name = "cagr") val cagr: Double?,
    @Json(name = "win_rate") val winRate: Double?,
    @Json(name = "profit_factor") val profitFactor: Double?,
    @Json(name = "avg_trade_return") val avgTradeReturn: BigDecimal?,
)
