package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.PortfolioApi
import com.tradingplatform.app.data.local.db.dao.PnlDao
import com.tradingplatform.app.data.local.db.dao.PositionDao
import com.tradingplatform.app.data.model.toDomain
import com.tradingplatform.app.data.model.toEntity
import com.tradingplatform.app.domain.model.NavSummary
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.PnlSummary
import com.tradingplatform.app.domain.model.Position
import com.tradingplatform.app.domain.model.PositionStatus
import com.tradingplatform.app.domain.model.Transaction
import com.tradingplatform.app.domain.repository.PortfolioRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PortfolioRepositoryImpl @Inject constructor(
    private val portfolioApi: PortfolioApi,
    private val positionDao: PositionDao,
    private val pnlDao: PnlDao,
) : PortfolioRepository {

    // TTL positions : 5 min (CLAUDE.md §2 — Stratégie cache Room)
    private val POSITION_TTL_MS = 5 * 60 * 1000L

    // TTL PnL snapshots : 5 min
    private val PNL_TTL_MS = 5 * 60 * 1000L

    override suspend fun getPositions(portfolioId: Int, status: PositionStatus): Result<List<Position>> =
        runCatching {
            val response = portfolioApi.getPositions(portfolioId, status.toApiString())
            if (!response.isSuccessful) {
                error("Get positions failed: HTTP ${response.code()}")
            }
            val positions = response.body()?.positions?.map { it.toDomain() } ?: emptyList()

            // Purge Room APRÈS sync réussie — jamais avant (CLAUDE.md §2 Politique de rétention)
            val now = System.currentTimeMillis()
            positionDao.upsertAll(positions.map { it.toEntity(syncedAt = now) })
            positionDao.deleteOlderThan(now - POSITION_TTL_MS)

            positions
        }

    override suspend fun getPnl(portfolioId: Int, period: PnlPeriod): Result<PnlSummary> =
        runCatching {
            val response = portfolioApi.getPnl(portfolioId, period.toApiString())
            if (!response.isSuccessful) {
                error("Get PnL failed: HTTP ${response.code()}")
            }
            val pnl = response.body()?.toDomain() ?: error("Empty PnL response")

            // Purge Room APRÈS sync réussie
            val now = System.currentTimeMillis()
            pnlDao.upsert(pnl.toEntity(period, syncedAt = now))
            pnlDao.deleteOlderThan(now - PNL_TTL_MS)

            pnl
        }

    override suspend fun getNav(portfolioId: Int): Result<NavSummary> = runCatching {
        val response = portfolioApi.getNav(portfolioId)
        if (!response.isSuccessful) {
            error("Get NAV failed: HTTP ${response.code()}")
        }
        response.body()?.toDomain() ?: error("Empty NAV response")
    }

    override suspend fun getTransactions(
        portfolioId: Int,
        limit: Int,
        offset: Int,
        symbol: String?,
    ): Result<List<Transaction>> = runCatching {
        val response = portfolioApi.getTransactions(portfolioId, limit, offset, symbol)
        if (!response.isSuccessful) {
            error("Get transactions failed: HTTP ${response.code()}")
        }
        response.body()?.transactions?.map { it.toDomain() } ?: emptyList()
    }
}
