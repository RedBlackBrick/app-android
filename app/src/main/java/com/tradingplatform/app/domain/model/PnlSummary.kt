package com.tradingplatform.app.domain.model

import java.math.BigDecimal

/**
 * Performance summary sourced from `GET /v1/portfolios/{id}/performance`
 * (backend `PerformanceMetrics` schema).
 *
 * All fields are nullable because the backend may not have enough data to
 * compute them (e.g. brand-new portfolio with no trades).
 *
 * Realized and unrealized P&L are sourced separately from
 * `GET /v1/portfolios/{id}` and available via [NavSummary]
 * (`totalRealizedPnl`, `totalUnrealizedPnl`).
 */
data class PnlSummary(
    val totalReturn: BigDecimal?,
    val totalReturnPct: Double?,
    val sharpeRatio: Double?,
    val sortinoRatio: Double?,
    val maxDrawdown: Double?,
    val volatility: Double?,
    val cagr: Double?,
    val winRate: Double?,
    val profitFactor: Double?,
    val avgTradeReturn: BigDecimal?,
    /** Data points for sparkline chart visualization on the Dashboard. */
    val sparklinePoints: List<BigDecimal> = emptyList(),
)
