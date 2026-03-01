package com.tradingplatform.app.domain.model

import java.math.BigDecimal
import java.time.Instant

data class Position(
    val id: Int,
    val symbol: String,
    val quantity: BigDecimal,
    val avgPrice: BigDecimal,
    val currentPrice: BigDecimal,
    val unrealizedPnl: BigDecimal,
    val unrealizedPnlPercent: Double,
    val status: PositionStatus,
    val openedAt: Instant,
)
