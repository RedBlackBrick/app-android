package com.tradingplatform.app.domain.model

import java.math.BigDecimal

/**
 * Net Asset Value summary sourced from `GET /v1/portfolios/{id}`
 * (backend `PortfolioDetailResponse` schema).
 *
 * [currentValue] is the portfolio NAV (total current value including cash and
 * open positions). [cashBalance] is the uninvested cash. [totalRealizedPnl]
 * and [totalUnrealizedPnl] are the aggregate P&L across all trades/positions.
 */
data class NavSummary(
    val currentValue: BigDecimal,
    val cashBalance: BigDecimal,
    val totalRealizedPnl: BigDecimal,
    val totalUnrealizedPnl: BigDecimal,
)
