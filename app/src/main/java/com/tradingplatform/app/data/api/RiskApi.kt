package com.tradingplatform.app.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * Mirror of the backend ``PortfolioCircuitBreakerStatus`` schema. Returned by
 * ``GET /v1/risk/portfolios/{portfolio_id}/circuit-breaker/status``.
 */
@JsonClass(generateAdapter = true)
data class PortfolioCircuitBreakerStatusDto(
    @Json(name = "portfolio_id") val portfolioId: String,
    @Json(name = "enabled") val enabled: Boolean,
    @Json(name = "state") val state: String,
    @Json(name = "count") val count: Int,
    @Json(name = "threshold") val threshold: Int,
    @Json(name = "window_seconds") val windowSeconds: Int,
    @Json(name = "ttl_seconds") val ttlSeconds: Int? = null,
    @Json(name = "redis_unavailable") val redisUnavailable: Boolean = false,
)

interface RiskApi {
    @GET("v1/risk/portfolios/{portfolio_id}/circuit-breaker/status")
    suspend fun getPortfolioCircuitBreakerStatus(
        @Path("portfolio_id") portfolioId: String,
    ): Response<PortfolioCircuitBreakerStatusDto>
}
