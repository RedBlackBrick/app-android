package com.tradingplatform.app.domain.model

import java.math.BigDecimal
import java.time.Instant

data class Transaction(
    val id: Long,
    val symbol: String,
    val action: String,
    val quantity: BigDecimal,
    val price: BigDecimal,
    val commission: BigDecimal,
    val total: BigDecimal,
    val executedAt: Instant,
)
