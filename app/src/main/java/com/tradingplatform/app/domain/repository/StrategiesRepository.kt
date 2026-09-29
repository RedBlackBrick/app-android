package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.PortfolioStrategyEntry
import com.tradingplatform.app.domain.model.PortfolioStrategyLink
import com.tradingplatform.app.domain.model.WriteOutcome

interface StrategiesRepository {
    suspend fun listPortfolioStrategies(portfolioId: String): Result<List<PortfolioStrategyLink>>

    /**
     * Liens du portefeuille (actifs ET inactifs) joints à leurs noms via le catalogue paginé
     * `GET /v1/strategies` (page maximale, pages fusionnées, borne de sécurité de 500 stratégies).
     * Une stratégie absente du catalogue, ou un catalogue illisible, donne `name = null` : seule
     * la lecture des liens peut faire échouer le résultat.
     */
    suspend fun listPortfolioStrategyEntries(portfolioId: String): Result<List<PortfolioStrategyEntry>>

    /**
     * Met en pause (`active = false`) ou reprend (`true`) un lien portefeuille-stratégie
     * (`PATCH …/strategies/{strategy_id}` corps `{"is_active": …}`). Écriture : **jamais rejouée**.
     *
     * 2xx → `success(CONFIRMED)` ; 5xx ou timeout / IOException après envoi →
     * `success(REQUESTED_UNCONFIRMED)` (l'appelant relit l'état) ; 409, 4xx, VPN absent ou échec
     * avant envoi → `failure`.
     */
    suspend fun setPortfolioStrategyActive(
        portfolioId: String,
        strategyId: String,
        active: Boolean,
    ): Result<WriteOutcome>
}
