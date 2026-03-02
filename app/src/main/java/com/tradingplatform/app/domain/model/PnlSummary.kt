package com.tradingplatform.app.domain.model

import java.math.BigDecimal

data class PnlSummary(
    val realizedPnl: BigDecimal,
    val unrealizedPnl: BigDecimal,
    val totalPnl: BigDecimal,
    val totalPnlPercent: Double,
    val tradesCount: Int,
    val winningTrades: Int,
    val losingTrades: Int,
    /** Data points for sparkline chart visualization on the Dashboard. */
    val sparklinePoints: List<BigDecimal> = emptyList(),
)
