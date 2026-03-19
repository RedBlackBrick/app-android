package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.math.BigDecimal

/**
 * Nested portfolio summary used inside [PortfolioDetailDto].
 * Maps to backend `PortfolioResponse` schema (subset of fields needed by the Android app).
 */
@JsonClass(generateAdapter = true)
data class PortfolioSummaryDto(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String,
    @Json(name = "current_value") val currentValue: BigDecimal,
    @Json(name = "cash_balance") val cashBalance: BigDecimal,
    @Json(name = "currency_code") val currencyCode: String = "EUR",
)

/**
 * DTO for `GET /v1/portfolios/{portfolio_id}`
 * Maps to backend `PortfolioDetailResponse` schema.
 *
 * The [portfolio] field carries NAV and cash balance.
 * [totalRealizedPnl] and [totalUnrealizedPnl] are the aggregate P&L across all positions.
 * Together they feed [com.tradingplatform.app.domain.model.NavSummary] on the Dashboard.
 */
@JsonClass(generateAdapter = true)
data class PortfolioDetailDto(
    @Json(name = "portfolio") val portfolio: PortfolioSummaryDto,
    @Json(name = "total_realized_pnl") val totalRealizedPnl: BigDecimal,
    @Json(name = "total_unrealized_pnl") val totalUnrealizedPnl: BigDecimal,
)
