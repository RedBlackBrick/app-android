package com.tradingplatform.app.domain.model

import java.math.BigDecimal
import java.time.Instant

data class NavSummary(
    val nav: BigDecimal,
    val cash: BigDecimal,
    val positionsValue: BigDecimal,
    val timestamp: Instant,
)
