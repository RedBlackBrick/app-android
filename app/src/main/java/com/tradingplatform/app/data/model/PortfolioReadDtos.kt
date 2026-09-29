package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/*
 * DTO des lectures « vue d'ensemble » : batch P&L, dashboard/overview, value-history, broker-connection.
 *
 * Conventions (contrat backend §0.3 / §9) : les `Decimal` Pydantic sortent en CHAÎNE JSON → String
 * ici, BigDecimal au mapping (`PortfolioReadMappers.kt`) ; les champs optionnels du backend sont
 * nullables avec défaut pour qu'un champ manquant ne fasse jamais planter Moshi.
 * Attention aux unités : `batch/pnl.pnl_pct` est une FRACTION, `dashboard.today_pnl_pct` un
 * POURCENTAGE (non lu par l'app : le P&L de la période ALL est recalculé depuis capital initial).
 */

/** Corps de `POST /v1/portfolios/batch/pnl` (1..50 ids ; `period` = day | week | month). */
@JsonClass(generateAdapter = true)
data class BatchPnlRequestDto(
    @Json(name = "portfolio_ids") val portfolioIds: List<String>,
    @Json(name = "period") val period: String,
)

/** Réponse de `POST /v1/portfolios/batch/pnl` : `data` indexé par UUID de portefeuille. */
@JsonClass(generateAdapter = true)
data class BatchPnlResponseDto(
    @Json(name = "data") val data: Map<String, BatchPnlItemDto> = emptyMap(),
)

/**
 * P&L d'un portefeuille sur la période. `pnl_pct` = FRACTION (`-0.00826` = -0,826 %).
 * `currency_code` vaut `null` pour un id demandé mais introuvable (montants alors à « 0 »).
 * `previous_value` peut être égal à `current_value` (pas de snapshot antérieur → P&L 0).
 */
@JsonClass(generateAdapter = true)
data class BatchPnlItemDto(
    @Json(name = "portfolio_id") val portfolioId: String? = null,
    @Json(name = "pnl_amount") val pnlAmount: String? = null,
    @Json(name = "pnl_pct") val pnlPct: Double? = null,
    @Json(name = "previous_value") val previousValue: String? = null,
    @Json(name = "current_value") val currentValue: String? = null,
    @Json(name = "currency_code") val currencyCode: String? = null,
)

/** `GET /v1/dashboard/overview` — seules les valeurs par portefeuille sont lues. */
@JsonClass(generateAdapter = true)
data class DashboardOverviewDto(
    @Json(name = "portfolios") val portfolios: List<DashboardPortfolioDto> = emptyList(),
)

/** Un portefeuille de `dashboard/overview` (montants Decimal en chaînes). */
@JsonClass(generateAdapter = true)
data class DashboardPortfolioDto(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String? = null,
    @Json(name = "currency_code") val currencyCode: String? = null,
    @Json(name = "current_value") val currentValue: String? = null,
    @Json(name = "initial_capital") val initialCapital: String? = null,
)

/**
 * `GET /v1/portfolios/{id}/value-history`. Pas de pagination côté backend : la requête doit toujours
 * être bornée par `start_date` + `granularity` (cf. `PortfolioRepositoryImpl.getNavCurve`).
 */
@JsonClass(generateAdapter = true)
data class ValueHistoryResponseDto(
    @Json(name = "items") val items: List<ValueHistoryPointDto> = emptyList(),
)

/** Un snapshot de NAV : `total_value` (chaîne, cash inclus) à `recorded_at` (`Z` ou `+00:00`). */
@JsonClass(generateAdapter = true)
data class ValueHistoryPointDto(
    @Json(name = "total_value") val totalValue: String? = null,
    @Json(name = "recorded_at") val recordedAt: String? = null,
)

/**
 * `GET /v1/portfolios/{id}/broker-connection` (200, corps `null` quand aucune connexion).
 * `connection_status` = statut de configuration (active, inactive, error, maintenance, revoked,
 * pending), pas une santé live. Aucun secret n'est renvoyé.
 */
@JsonClass(generateAdapter = true)
data class PortfolioBrokerConnectionDto(
    @Json(name = "broker_code") val brokerCode: String? = null,
    @Json(name = "connection_status") val connectionStatus: String? = null,
)
