package com.tradingplatform.app.domain.model

import java.math.BigDecimal

/**
 * P&L summary of one period — live via `getPnlSummary`, or from the `pnl_snapshots` cache.
 * DAY / WEEK / MONTH come from `POST /v1/portfolios/batch/pnl` (a real period P&L: amount and
 * fraction only, no trade counters); ALL / YEAR come from `GET /v1/portfolios/{id}/pnl`
 * (backend `PnlResponse`, a "since inception" P&L).
 *
 * Units: [totalReturnPct] is a **fraction** (0.0175 = 1.75 %). [winRate] is always null: the
 * backend's `winning_trades` is not a win rate (contrat §8.1 / §9).
 * The risk ratios (sharpe, sortino, drawdown, volatility, cagr, profit factor,
 * avg trade return) are not provided by these endpoints and are always null on this path —
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
