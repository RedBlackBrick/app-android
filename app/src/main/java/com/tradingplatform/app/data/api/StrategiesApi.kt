package com.tradingplatform.app.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * Subset of ``PortfolioStrategyLink`` from the backend — only the fields the
 * mobile app actually consumes (``is_active`` for the Dashboard count tile).
 * Other fields (allocation_pct, custom_parameters, …) are intentionally
 * dropped to keep the wire-decoding cost low.
 */
@JsonClass(generateAdapter = true)
data class PortfolioStrategyLinkDto(
    @Json(name = "portfolio_id") val portfolioId: String,
    @Json(name = "strategy_id") val strategyId: String,
    @Json(name = "is_active") val isActive: Boolean = true,
)

interface StrategiesApi {
    @GET("v1/portfolios/{portfolio_id}/strategies")
    suspend fun listPortfolioStrategies(
        @Path("portfolio_id") portfolioId: String,
    ): Response<List<PortfolioStrategyLinkDto>>
}
