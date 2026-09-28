package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.Cached
import com.tradingplatform.app.domain.model.NavSummary
import com.tradingplatform.app.domain.model.PerformanceMetrics
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.PnlSummary
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
     * `GET /pnl?period=…` — seul chemin PnL. En cas de succès, persiste aussi la ligne
     * `pnl_snapshots` de la période (lue par `PnlWidget`).
     */
    suspend fun getPnlSummary(portfolioId: String, period: PnlPeriod): Result<PnlSummary>
    suspend fun getPerformance(portfolioId: String): Result<PerformanceMetrics>
    suspend fun getNav(portfolioId: String): Result<NavSummary>
    suspend fun getTransactions(
        portfolioId: String,
        limit: Int = 50,
        offset: Int = 0,
        symbol: String? = null,
    ): Result<List<Transaction>>
}
