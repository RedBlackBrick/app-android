package com.tradingplatform.app.domain.model

import java.math.BigDecimal
import java.time.Instant

data class Quote(
    val symbol: String,
    val price: BigDecimal,
    val bid: BigDecimal?,
    val ask: BigDecimal?,
    val volume: Long,
    val change: BigDecimal,
    val changePercent: Double,
    val timestamp: Instant,
    val source: String,
)
