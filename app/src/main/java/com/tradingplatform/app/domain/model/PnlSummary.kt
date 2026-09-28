package com.tradingplatform.app.domain.model

import java.math.BigDecimal

/**
 * P&L summary of one period, sourced from `GET /v1/portfolios/{id}/pnl?period=…`
 * (backend `PnlResponse`) — live via `getPnlSummary`, or from the `pnl_snapshots` cache.
 *
 * Units: [totalReturnPct] and [winRate] are **fractions** (0.0175 = 1.75 %).
 * The risk ratios (sharpe, sortino, drawdown, volatility, cagr, profit factor,
 * avg trade return) are not provided by `/pnl` and are always null on this path —
 * full metrics live in [PerformanceMetrics] (`/performance`).
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
    val tradesCount: Int? = null,
    val winningTrades: Int? = null,
    val losingTrades: Int? = null,
    /** Data points for sparkline chart visualization on the Dashboard. */
    val sparklinePoints: List<BigDecimal> = emptyList(),
)
