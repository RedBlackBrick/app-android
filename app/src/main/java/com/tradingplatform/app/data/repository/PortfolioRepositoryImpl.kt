package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.PortfolioApi
import com.tradingplatform.app.data.local.db.CacheTtl
import com.tradingplatform.app.data.local.db.dao.PnlDao
import com.tradingplatform.app.data.local.db.dao.PositionDao
import com.tradingplatform.app.data.model.toDomain
import com.tradingplatform.app.data.model.toEntity
import com.tradingplatform.app.data.model.toPerformanceMetrics
import com.tradingplatform.app.data.model.toPnlSummary
import com.tradingplatform.app.domain.model.Cached
import com.tradingplatform.app.domain.model.NavSummary
import com.tradingplatform.app.domain.model.PerformanceMetrics
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.PnlSummary
import com.tradingplatform.app.domain.model.Position
import com.tradingplatform.app.domain.model.PositionStatus
import com.tradingplatform.app.domain.model.Transaction
import com.tradingplatform.app.domain.repository.PortfolioRepository
import com.tradingplatform.app.domain.util.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class PortfolioRepositoryImpl @Inject constructor(
    private val portfolioApi: PortfolioApi,
    private val positionDao: PositionDao,
    private val pnlDao: PnlDao,
) : PortfolioRepository {

    override suspend fun getPosition(
        portfolioId: String,
        positionId: Int,
        forceRefresh: Boolean,
    ): Result<Cached<Position>> = runCatchingCancellable {
        val now = System.currentTimeMillis()
        val cached = positionDao.getById(positionId)

        // Cache servi uniquement s'il est frais (TTL positions — CacheTtl / CLAUDE.md §2)
        if (cached != null && !forceRefresh && CacheTtl.isFresh(cached.syncedAt, CacheTtl.POSITIONS_MS, now)) {
            return@runCatchingCancellable Cached(cached.toDomain(), cached.syncedAt)
        }

        // Cache absent, périmé ou refresh forcé — fetch `status=all` pour que les positions
        // fermées (navigables depuis le filtre « Fermées ») se résolvent aussi.
        val positions = try {
            fetchAndCachePositions(portfolioId, PositionStatus.ALL, now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Réseau indisponible : afficher la ligne périmée avec son vrai horodatage
            // (CacheTimestamp la marque « offline ») plutôt qu'une erreur bloquante.
            if (cached != null) return@runCatchingCancellable Cached(cached.toDomain(), cached.syncedAt)
            throw e
        }

        val position = positions.find { it.id == positionId }
            ?: error("Position $positionId not found")
        Cached(position, now)
    }

    override suspend fun getPositions(portfolioId: String, status: PositionStatus): Result<List<Position>> =
        runCatchingCancellable { fetchAndCachePositions(portfolioId, status, System.currentTimeMillis()) }

    /**
     * `GET /positions?status=…` puis upsert + purge Room.
     * Purge APRÈS sync réussie — jamais avant (CLAUDE.md §2 Politique de rétention) ;
     * transaction atomique : upsert + purge en un seul commit SQLite.
     */
    private suspend fun fetchAndCachePositions(
        portfolioId: String,
        status: PositionStatus,
        now: Long,
    ): List<Position> {
        val response = portfolioApi.getPositions(portfolioId, status.toApiString())
        if (!response.isSuccessful) {
            error("Get positions failed: HTTP ${response.code()}")
        }
        val positions = response.body()?.map { it.toDomain() } ?: emptyList()
        positionDao.upsertAllAndPurge(
            positions.map { it.toEntity(syncedAt = now) },
            cutoffMillis = now - CacheTtl.POSITIONS_MS,
        )
        return positions
    }

    /**
     * Unique writer de `pnl_snapshots` : Dashboard et WidgetUpdateWorker passent tous deux
     * par `GetPnlUseCase` → ici. Une ligne par période (upsert REPLACE sur `period`).
     */
    override suspend fun getPnlSummary(portfolioId: String, period: PnlPeriod): Result<PnlSummary> =
        runCatchingCancellable {
            val response = portfolioApi.getPnl(portfolioId, period.toApiString())
            if (!response.isSuccessful) {
                error("Get PnL failed: HTTP ${response.code()}")
            }
            val dto = response.body() ?: error("Empty PnL response")

            // Purge Room APRÈS sync réussie — transaction atomique (upsert + purge).
            // Cutoff = rétention 24 h, PAS la fraîcheur 5 min : une ligne par période, une
            // purge à 5 min supprimerait les lignes des autres périodes (CacheTtl.PNL_RETENTION_MS).
            val now = System.currentTimeMillis()
            pnlDao.upsertAndPurge(
                dto.toEntity(period, syncedAt = now),
                cutoffMillis = now - CacheTtl.PNL_RETENTION_MS,
            )

            dto.toPnlSummary()
        }

    override suspend fun getPerformance(portfolioId: String): Result<PerformanceMetrics> =
        runCatchingCancellable {
            val response = portfolioApi.getPerformance(portfolioId)
            if (!response.isSuccessful) {
                error("Get performance failed: HTTP ${response.code()}")
            }
            response.body()?.toPerformanceMetrics()
                ?: error("Empty performance response")
        }

    override suspend fun getNav(portfolioId: String): Result<NavSummary> = runCatchingCancellable {
        val response = portfolioApi.getPortfolioDetail(portfolioId)
        if (!response.isSuccessful) {
            error("Get portfolio detail failed: HTTP ${response.code()}")
        }
        response.body()?.toDomain() ?: error("Empty portfolio detail response")
    }

    override suspend fun getTransactions(
        portfolioId: String,
        limit: Int,
        offset: Int,
        symbol: String?,
    ): Result<List<Transaction>> = runCatchingCancellable {
        val response = portfolioApi.getTransactions(portfolioId, limit, offset, symbol)
        if (!response.isSuccessful) {
            error("Get transactions failed: HTTP ${response.code()}")
        }
        response.body()?.transactions?.map { it.toDomain() } ?: emptyList()
    }
}
