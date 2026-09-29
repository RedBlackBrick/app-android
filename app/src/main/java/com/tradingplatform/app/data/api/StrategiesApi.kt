package com.tradingplatform.app.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

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

/**
 * Élément de `GET /v1/strategies` réduit à ce que l'app consomme : l'identifiant (= `strategy_id`
 * des liens portefeuille) et le nom. Les liens portefeuille-stratégie n'ont PAS de nom (contrat
 * backend §4.1/§9.8) — la jointure se fait ici. Tous les autres champs (métriques, propriétaire,
 * règles…) sont ignorés à dessein.
 */
@JsonClass(generateAdapter = true)
data class StrategyListItemDto(
    @Json(name = "id") val id: String = "",
    @Json(name = "name") val name: String? = null,
)

/** Enveloppe paginée de `GET /v1/strategies` : `total` = total des lignes filtrées. */
@JsonClass(generateAdapter = true)
data class StrategyListResponseDto(
    @Json(name = "items") val items: List<StrategyListItemDto> = emptyList(),
    @Json(name = "total") val total: Int = 0,
    @Json(name = "offset") val offset: Int = 0,
    @Json(name = "limit") val limit: Int = 0,
)

interface StrategiesApi {
    @GET("v1/portfolios/{portfolio_id}/strategies")
    suspend fun listPortfolioStrategies(
        @Path("portfolio_id") portfolioId: String,
    ): Response<List<PortfolioStrategyLinkDto>>

    /**
     * Catalogue des stratégies de l'utilisateur + stratégies publiques (`limit` 1..200, défaut 50
     * côté backend). Les paramètres sont explicites : l'appelant demande toujours le `limit` maximal.
     */
    @GET("v1/strategies")
    suspend fun listStrategies(
        @Query("limit") limit: Int,
        @Query("offset") offset: Int,
    ): Response<StrategyListResponseDto>
}
