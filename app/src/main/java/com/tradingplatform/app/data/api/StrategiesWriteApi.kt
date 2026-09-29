package com.tradingplatform.app.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.PATCH
import retrofit2.http.Path

/**
 * Corps de `PATCH /v1/portfolios/{portfolio_id}/strategies/{strategy_id}` limité à `is_active`
 * (pause / reprise d'un lien portefeuille-stratégie). Le backend accepte d'autres champs
 * (`allocation_pct`, `budget_allocated`, …) : ils ne sont jamais envoyés depuis le mobile
 * (édition de stratégie = web), et un champ absent reste inchangé côté serveur.
 */
@JsonClass(generateAdapter = true)
data class PortfolioStrategyActiveUpdateDto(
    @Json(name = "is_active") val isActive: Boolean,
)

/**
 * Écritures sur les liens portefeuille-stratégie. Interface DÉDIÉE, construite sur le Retrofit
 * `@Named("write")` (jamais de retry automatique) et fournie par `di/OrdersStrategiesModule.kt`.
 *
 * Le 200 renvoie le lien complet, ignoré ici (`Response<Unit>`) : seul le code HTTP compte.
 */
interface StrategiesWriteApi {
    @PATCH("v1/portfolios/{portfolio_id}/strategies/{strategy_id}")
    suspend fun setActive(
        @Path("portfolio_id") portfolioId: String,
        @Path("strategy_id") strategyId: String,
        @Body body: PortfolioStrategyActiveUpdateDto,
    ): Response<Unit>
}
