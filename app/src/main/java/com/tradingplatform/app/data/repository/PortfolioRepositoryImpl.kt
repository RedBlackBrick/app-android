package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.PortfolioApi
import com.tradingplatform.app.data.local.db.CacheTtl
import com.tradingplatform.app.data.local.db.dao.PnlDao
import com.tradingplatform.app.data.local.db.dao.PositionDao
import com.tradingplatform.app.data.model.BatchPnlItemDto
import com.tradingplatform.app.data.model.BatchPnlRequestDto
import com.tradingplatform.app.data.model.toDomain
import com.tradingplatform.app.data.model.toEntity
import com.tradingplatform.app.data.model.toNavPointOrNull
import com.tradingplatform.app.data.model.toOverviewItem
import com.tradingplatform.app.data.model.toPerformanceMetrics
import com.tradingplatform.app.data.model.toPnlSummary
import com.tradingplatform.app.domain.model.Cached
import com.tradingplatform.app.domain.model.NavCurve
import com.tradingplatform.app.domain.model.NavSummary
import com.tradingplatform.app.domain.model.PerformanceMetrics
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.PnlSummary
import com.tradingplatform.app.domain.model.Portfolio
import com.tradingplatform.app.domain.model.PortfolioBrokerStatus
import com.tradingplatform.app.domain.model.PortfolioOverviewItem
import com.tradingplatform.app.domain.model.Position
import com.tradingplatform.app.domain.model.PositionStatus
import com.tradingplatform.app.domain.model.Transaction
import com.tradingplatform.app.domain.repository.PortfolioRepository
import com.tradingplatform.app.domain.repository.PortfolioSelectionRepository
import com.tradingplatform.app.domain.util.runCatchingCancellable
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Le constructeur primaire (avec [Clock]) est `internal` : il sert aux tests, qui figent le temps
 * pour vérifier la fenêtre de `getNavCurve`. Hilt passe par le constructeur secondaire `@Inject`.
 */
@Singleton
class PortfolioRepositoryImpl internal constructor(
    private val portfolioApi: PortfolioApi,
    private val positionDao: PositionDao,
    private val pnlDao: PnlDao,
    private val portfolioSelection: PortfolioSelectionRepository,
    private val clock: Clock,
) : PortfolioRepository {

    @Inject
    constructor(
        portfolioApi: PortfolioApi,
        positionDao: PositionDao,
        pnlDao: PnlDao,
        portfolioSelection: PortfolioSelectionRepository,
    ) : this(portfolioApi, positionDao, pnlDao, portfolioSelection, Clock.systemUTC())

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
     *
     * Le backend ignore `status` : il renvoie toujours les positions actives ET inactives
     * (`is_active`). Room reçoit donc tout ce qui est renvoyé (le détail d'une position fermée
     * doit rester résoluble), mais la liste rendue à l'appelant est filtrée ici — sinon l'onglet
     * « Ouvertes » afficherait aussi les positions fermées. Si le backend filtre un jour lui-même,
     * ce filtre devient un no-op.
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
        return if (status == PositionStatus.ALL) positions else positions.filter { it.status == status }
    }

    /**
     * Unique writer de `pnl_snapshots` : Dashboard et WidgetUpdateWorker passent tous deux
     * par `GetPnlUseCase` → ici. Une ligne par période (upsert REPLACE sur `period`).
     *
     * Sémantique par période (contrat backend §8.1 / §9) : `/pnl?period=` est un P&L « depuis la
     * création » que `period` ne filtre pas — seul `batch/pnl` donne un vrai P&L jour/semaine/mois.
     * DAY / WEEK / MONTH → [getPeriodPnlSummary] (`batch/pnl`) ; ALL / YEAR → [getLifetimePnlSummary].
     */
    override suspend fun getPnlSummary(portfolioId: String, period: PnlPeriod): Result<PnlSummary> =
        runCatchingCancellable {
            when (period) {
                PnlPeriod.DAY, PnlPeriod.WEEK, PnlPeriod.MONTH -> getPeriodPnlSummary(portfolioId, period)
                PnlPeriod.YEAR, PnlPeriod.ALL -> getLifetimePnlSummary(portfolioId, period)
            }
        }

    /** DAY / WEEK / MONTH : montant et fraction du `batch/pnl` (ids = [portfolioId]). */
    private suspend fun getPeriodPnlSummary(portfolioId: String, period: PnlPeriod): PnlSummary {
        // `currency_code` null = id inconnu du backend : montants à « 0 » sans signification.
        val item = fetchBatchPnl(listOf(portfolioId), period)[portfolioId]
            ?.takeIf { it.currencyCode != null }
            ?: error("Portfolio $portfolioId missing from batch PnL")
        val summary = item.toPnlSummary() ?: error("Batch PnL amount missing")

        // Purge Room APRÈS sync réussie — transaction atomique (upsert + purge), cutoff = rétention
        // 24 h (CacheTtl.PNL_RETENTION_MS), pas la fraîcheur 5 min : une ligne par période.
        val now = System.currentTimeMillis()
        val entity = item.toEntity(period, syncedAt = now) ?: error("Batch PnL amount missing")
        pnlDao.upsertAndPurge(entity, cutoffMillis = now - CacheTtl.PNL_RETENTION_MS)
        return summary
    }

    /** ALL / YEAR : `GET /pnl` — P&L depuis la création (`total_pnl_percent` en % → fraction). */
    private suspend fun getLifetimePnlSummary(portfolioId: String, period: PnlPeriod): PnlSummary {
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

        return dto.toPnlSummary()
    }

    /**
     * `POST /v1/portfolios/batch/pnl` par paquets de [BATCH_PNL_MAX_IDS] (limite backend 1..50).
     * Les ids que le backend exclut (non possédés) sont simplement absents du résultat.
     */
    private suspend fun fetchBatchPnl(
        portfolioIds: List<String>,
        period: PnlPeriod,
    ): Map<String, BatchPnlItemDto> {
        require(period in BATCH_PNL_PERIODS) { "batch/pnl only supports day, week and month" }
        val merged = LinkedHashMap<String, BatchPnlItemDto>()
        for (chunk in portfolioIds.chunked(BATCH_PNL_MAX_IDS)) {
            val response = portfolioApi.getBatchPnl(BatchPnlRequestDto(chunk, period.toApiString()))
            if (!response.isSuccessful) {
                error("Get batch PnL failed: HTTP ${response.code()}")
            }
            merged.putAll(response.body()?.data.orEmpty())
        }
        return merged
    }

    override suspend fun getPortfoliosOverview(period: PnlPeriod): Result<List<PortfolioOverviewItem>> =
        runCatchingCancellable {
            val known = knownPortfolios()
            when (period) {
                PnlPeriod.DAY, PnlPeriod.WEEK, PnlPeriod.MONTH -> {
                    val batch = fetchBatchPnl(known.map { it.id }, period)
                    known.mapNotNull { portfolio -> batch[portfolio.id]?.toOverviewItem(portfolio) }
                }
                PnlPeriod.YEAR, PnlPeriod.ALL -> {
                    // Pas de P&L « année » côté backend (batch/pnl refuse ytd/all) : P&L depuis la
                    // création = valeur courante − capital initial.
                    val response = portfolioApi.getDashboardOverview()
                    if (!response.isSuccessful) {
                        error("Get dashboard overview failed: HTTP ${response.code()}")
                    }
                    val byId = response.body()?.portfolios.orEmpty().associateBy { it.id }
                    known.mapNotNull { portfolio -> byId[portfolio.id]?.toOverviewItem(portfolio) }
                }
            }
        }

    /** Portefeuilles connus ; liste vide → `refresh()` d'abord (échec propagé, ex. aucun portefeuille). */
    private suspend fun knownPortfolios(): List<Portfolio> {
        val current = portfolioSelection.portfolios.value
        if (current.isNotEmpty()) return current
        return portfolioSelection.refresh().getOrThrow()
    }

    override suspend fun getNavCurve(portfolioId: String, period: PnlPeriod): Result<NavCurve> =
        runCatchingCancellable {
            val now = clock.instant()
            val window = navCurveWindow(period, now)
            // Toujours borné : le backend n'a ni pagination ni limite (sans start_date = tout l'historique).
            val response = portfolioApi.getValueHistory(
                portfolioId = portfolioId,
                startDate = window.startDate.toString(),
                granularity = window.granularity,
                endDate = null,
            )
            if (!response.isSuccessful) {
                error("Get value history failed: HTTP ${response.code()}")
            }
            val keepFrom = window.keepFrom
            val points = response.body()?.items.orEmpty()
                .mapNotNull { it.toNavPointOrNull() }
                .filter { point -> keepFrom == null || !point.at.isBefore(keepFrom) }
                .sortedBy { it.at }
            NavCurve(downsample(points, NAV_CURVE_MAX_POINTS))
        }

    override suspend fun getBrokerStatus(portfolioId: String): Result<PortfolioBrokerStatus?> =
        runCatchingCancellable {
            val response = portfolioApi.getBrokerConnection(portfolioId)
            if (!response.isSuccessful) {
                error("Get broker connection failed: HTTP ${response.code()}")
            }
            // 200 avec corps `null` = aucune connexion configurée.
            response.body()?.toDomain()
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
        // Le backend ignore `symbol` (il renvoie les transactions de tout le portefeuille, récentes
        // d'abord). Pour un symbole : on balaie la plus grande page permise, on filtre ici puis on
        // applique offset/limit sur le résultat filtré — jamais côté serveur, sinon l'offset
        // s'appliquerait deux fois. Si le backend filtre un jour lui-même, le filtre est un no-op.
        val response = if (symbol == null) {
            portfolioApi.getTransactions(portfolioId, limit, offset, null)
        } else {
            portfolioApi.getTransactions(portfolioId, SYMBOL_SCAN_LIMIT, 0, symbol)
        }
        if (!response.isSuccessful) {
            error("Get transactions failed: HTTP ${response.code()}")
        }
        val all = response.body()?.transactions?.map { it.toDomain() } ?: emptyList()
        if (symbol == null) {
            all
        } else {
            all.filter { it.symbol.equals(symbol, ignoreCase = true) }.drop(offset).take(limit)
        }
    }

    private companion object {
        /** Plafond `limit` de `GET /transactions` côté backend (≤ 1000). */
        const val SYMBOL_SCAN_LIMIT = 1000

        /** `batch/pnl` accepte 1..50 ids par requête. */
        const val BATCH_PNL_MAX_IDS = 50

        /** `batch/pnl` refuse ytd / all / year (422). */
        val BATCH_PNL_PERIODS = setOf(PnlPeriod.DAY, PnlPeriod.WEEK, PnlPeriod.MONTH)
    }
}
