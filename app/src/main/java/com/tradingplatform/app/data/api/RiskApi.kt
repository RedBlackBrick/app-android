package com.tradingplatform.app.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

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

/**
 * `GET /v1/risk/kill-switch/active` (backend `RiskKillSwitchActiveResponse`) : drapeaux seuls
 * (pas de motif). `portfolio_active` : une entrée par portefeuille de l'utilisateur, clé = UUID.
 * `symbol_active` / `any_active` ne sont pas consommés.
 */
@JsonClass(generateAdapter = true)
data class KillSwitchActiveDto(
    @Json(name = "global_active") val globalActive: Boolean = false,
    @Json(name = "portfolio_active") val portfolioActive: Map<String, Boolean> = emptyMap(),
)

/** Entrée de `kill_switches_active` (backend `Risk360KillSwitch`) : `global`, `portfolio:<id>` ou `strategy:<id>`. */
@JsonClass(generateAdapter = true)
data class Risk360KillSwitchDto(
    @Json(name = "scope") val scope: String,
    @Json(name = "reason") val reason: String? = null,
    @Json(name = "activated_by") val activatedBy: String? = null,
)

/**
 * Sous-ensemble de `GET /v1/risk/portfolios/{id}/risk-360-summary` (backend
 * `Risk360SummaryResponse`) utilisé par le mobile. Unités (contrat backend §2.4 / §9.1) :
 * `drawdown_current_pct` est un POURCENTAGE (-12.4 = -12,4 %), `daily_pnl` (devise du portefeuille)
 * et `daily_loss_limit` (USD, `null` = aucune règle) sont des Decimal en chaîne.
 */
@JsonClass(generateAdapter = true)
data class Risk360SummaryDto(
    @Json(name = "drawdown_current_pct") val drawdownCurrentPct: Double? = null,
    @Json(name = "daily_pnl") val dailyPnl: String? = null,
    @Json(name = "daily_loss_limit") val dailyLossLimit: String? = null,
    @Json(name = "kill_switches_active") val killSwitchesActive: List<Risk360KillSwitchDto> = emptyList(),
)

/** Élément de `GET /v1/risk/violations` (backend `ViolationResponse`) : seul l'état de résolution sert. */
@JsonClass(generateAdapter = true)
data class RiskViolationDto(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "is_resolved") val isResolved: Boolean = false,
)

/**
 * Body of ``POST /v1/risk/portfolios/{portfolio_id}/kill-switch`` (backend
 * `PortfolioKillSwitchActivate`) : motif obligatoire, 1 à 500 caractères.
 */
@JsonClass(generateAdapter = true)
data class PortfolioKillSwitchActivateRequestDto(
    @Json(name = "reason") val reason: String,
)

interface RiskApi {
    @GET("v1/risk/portfolios/{portfolio_id}/circuit-breaker/status")
    suspend fun getPortfolioCircuitBreakerStatus(
        @Path("portfolio_id") portfolioId: String,
    ): Response<PortfolioCircuitBreakerStatusDto>

    @GET("v1/risk/kill-switch/active")
    suspend fun getKillSwitchActive(): Response<KillSwitchActiveDto>

    @GET("v1/risk/portfolios/{portfolio_id}/risk-360-summary")
    suspend fun getRisk360Summary(
        @Path("portfolio_id") portfolioId: String,
    ): Response<Risk360SummaryDto>

    /** Tableau JSON nu, sans total. `limit` : 1..1000 (défaut backend 100). */
    @GET("v1/risk/violations")
    suspend fun getViolations(
        @Query("portfolio_id") portfolioId: String,
        @Query("is_resolved") isResolved: Boolean = false,
        @Query("limit") limit: Int = 100,
    ): Response<List<RiskViolationDto>>
}
