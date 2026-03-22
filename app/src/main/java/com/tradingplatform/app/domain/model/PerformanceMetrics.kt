package com.tradingplatform.app.domain.model

import java.math.BigDecimal

/**
 * Detailed portfolio performance metrics sourced from
 * `GET /v1/portfolios/{id}/performance`.
 *
 * Distinct from [PnlSummary] which is a lighter model used on the Dashboard.
 * This model carries the full set of risk and return metrics displayed on the
 * dedicated PerformanceScreen.
 *
 * All fields are nullable because the backend may not have enough data to
 * compute them (e.g. brand-new portfolio with no trades).
 */
data class PerformanceMetrics(
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
)
