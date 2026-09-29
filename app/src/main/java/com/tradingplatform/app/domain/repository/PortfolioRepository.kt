package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.Cached
import com.tradingplatform.app.domain.model.NavCurve
import com.tradingplatform.app.domain.model.NavSummary
import com.tradingplatform.app.domain.model.PerformanceMetrics
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.PnlSummary
import com.tradingplatform.app.domain.model.PortfolioBrokerStatus
import com.tradingplatform.app.domain.model.PortfolioOverviewItem
import com.tradingplatform.app.domain.model.Position
import com.tradingplatform.app.domain.model.PositionStatus
import com.tradingplatform.app.domain.model.Transaction

interface PortfolioRepository {
    suspend fun getPositions(portfolioId: String, status: PositionStatus): Result<List<Position>>
    /**
     * Une position (ouverte ou fermée). Sert le cache Room uniquement s'il est frais
     * (< `CacheTtl.POSITIONS_MS`) et que [forceRefresh] est faux ; sinon re-fetch réseau avec
     * `status=all` pour que les positions fermées se résolvent. Si le réseau échoue et qu'une
     * ligne (périmée) existe, elle est renvoyée avec son vrai `syncedAt`.
     */
    suspend fun getPosition(
        portfolioId: String,
        positionId: Int,
        forceRefresh: Boolean = false,
    ): Result<Cached<Position>>
    /**
     * Seul chemin PnL. En cas de succès, persiste aussi la ligne `pnl_snapshots` de la période
     * (lue par `PnlWidget`).
     *
     * Sémantique par période — `GET /pnl?period=` est un P&L « depuis la création » que `period`
     * ne filtre pas (contrat backend §8.1) :
     * - DAY / WEEK / MONTH : montant et pourcentage (`totalReturn`, `totalReturnPct` en FRACTION)
     *   viennent de `POST /v1/portfolios/batch/pnl` (ids = [portfolioId]) ; ni ratios, ni compteurs
     *   de trades ;
     * - ALL / YEAR : `GET /pnl` (P&L depuis la création ; `total_pnl_percent` en pourcentage → /100).
     *
     * `winRate` vaut toujours `null` : `winning_trades` du backend n'est pas un taux de réussite.
     */
    suspend fun getPnlSummary(portfolioId: String, period: PnlPeriod): Result<PnlSummary>

    /**
     * Valeur courante et P&L de la période de TOUS les portefeuilles connus
     * (`PortfolioSelectionRepository.portfolios`, dans l'ordre de la liste ; refresh d'abord si vide).
     * DAY / WEEK / MONTH : `POST /v1/portfolios/batch/pnl`. ALL / YEAR : `GET /v1/dashboard/overview`
     * (P&L = valeur courante − capital initial, depuis la création). `periodPnlPct` est une FRACTION.
     */
    suspend fun getPortfoliosOverview(period: PnlPeriod): Result<List<PortfolioOverviewItem>>

    /**
     * Courbe de NAV d'un portefeuille (`GET /v1/portfolios/{id}/value-history`, toujours bornée :
     * DAY = 24 h glissantes en `raw`, WEEK 7 j, MONTH 30 j, ALL/YEAR 365 j en `daily`), plafonnée à
     * ~120 points par échantillonnage régulier. Points triés par temps croissant ; peut être vide.
     */
    suspend fun getNavCurve(portfolioId: String, period: PnlPeriod): Result<NavCurve>

    /**
     * Connexion broker du portefeuille (`GET /v1/portfolios/{id}/broker-connection`, lecture seule) ;
     * `Result.success(null)` quand aucune connexion n'est configurée.
     */
    suspend fun getBrokerStatus(portfolioId: String): Result<PortfolioBrokerStatus?>
    suspend fun getPerformance(portfolioId: String): Result<PerformanceMetrics>
    suspend fun getNav(portfolioId: String): Result<NavSummary>
    suspend fun getTransactions(
        portfolioId: String,
        limit: Int = 50,
        offset: Int = 0,
        symbol: String? = null,
    ): Result<List<Transaction>>
}
