package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.PortfolioApi
import com.tradingplatform.app.data.local.db.CacheTtl
import com.tradingplatform.app.data.local.db.dao.PnlDao
import com.tradingplatform.app.data.local.db.dao.PositionDao
import com.tradingplatform.app.data.local.db.entity.PnlSnapshotEntity
import com.tradingplatform.app.data.local.db.entity.PositionEntity
import com.tradingplatform.app.data.model.PerformanceResponseDto
import com.tradingplatform.app.data.model.PnlResponseDto
import com.tradingplatform.app.data.model.PositionDto
import com.tradingplatform.app.data.model.TransactionDto
import com.tradingplatform.app.data.model.TransactionListResponseDto
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.PositionStatus
import com.tradingplatform.app.domain.repository.PortfolioSelectionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.math.BigDecimal

/**
 * Audit finding #4 (NEW-pnlwidget-1) — the PnL widget reads `pnl_snapshots`
 * (`PnlWidget` → `pnlDao.getByPeriod`). `getPnlSummary` (reached by both the Dashboard and
 * the worker through `GetPnlUseCase`) is now the single writer of that table; the former
 * dead writer `getPnl` (fed by `/performance`) has been removed.
 *
 * Sémantique des périodes : DAY / WEEK / MONTH passent par `batch/pnl` (voir
 * [PortfolioRepositoryImplOverviewTest]) ; ce fichier couvre le chemin `/pnl` (ALL / YEAR),
 * les positions et les transactions.
 */
class PortfolioRepositoryImplTest {

    private val portfolioApi = mockk<PortfolioApi>()
    private val positionDao = mockk<PositionDao>(relaxed = true)
    private val pnlDao = mockk<PnlDao>(relaxed = true)

    private val portfolioSelection = mockk<PortfolioSelectionRepository>(relaxed = true)

    private val repository = PortfolioRepositoryImpl(portfolioApi, positionDao, pnlDao, portfolioSelection)

    private val pnlFixture = PnlResponseDto(
        period = "day",
        realizedPnl = BigDecimal("120.50"),
        unrealizedPnl = BigDecimal("-20.25"),
        totalPnl = BigDecimal("100.25"),
        totalPnlPercent = 1.75,
        tradesCount = 4,
        winningTrades = 3,
        losingTrades = 1,
    )

    private val performanceFixture = PerformanceResponseDto(
        totalReturn = BigDecimal("4500.00"),
        totalReturnPct = 0.045,
        sharpeRatio = 1.2,
        sortinoRatio = 1.5,
        maxDrawdown = 8.3,
        volatility = 0.12,
        cagr = 0.08,
        winRate = 0.6,
        profitFactor = 1.4,
        avgTradeReturn = BigDecimal("12.00"),
    )

    @Test
    fun `getPnlSummary ALL persists the since-inception snapshot read by the PnL widget`() = runTest {
        coEvery { portfolioApi.getPnl(any(), any()) } returns Response.success(pnlFixture)
        val entity = slot<PnlSnapshotEntity>()

        val result = repository.getPnlSummary("portfolio-1", PnlPeriod.ALL)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { portfolioApi.getPnl("portfolio-1", "all") }
        coVerify(exactly = 0) { portfolioApi.getBatchPnl(any()) }
        coVerify(exactly = 1) { pnlDao.upsertAndPurge(capture(entity), any()) }
        assertEquals("all", entity.captured.period)
        assertEquals("100.25", entity.captured.totalPnl)
        assertEquals(0.0175, entity.captured.totalPnlPercent, 1e-9)
        assertEquals(4, entity.captured.tradesCount)
    }

    @Test
    fun `getPnlSummary ALL returns the domain summary with percent converted to fraction and no winRate`() = runTest {
        coEvery { portfolioApi.getPnl(any(), any()) } returns Response.success(pnlFixture)

        val summary = repository.getPnlSummary("portfolio-1", PnlPeriod.ALL).getOrThrow()

        assertEquals(BigDecimal("100.25"), summary.totalReturn)
        assertEquals(0.0175, summary.totalReturnPct!!, 1e-9)
        // winning_trades / trades_count n'est pas un win rate (contrat §9) : jamais exposé.
        assertNull(summary.winRate)
    }

    @Test
    fun `getPnlSummary purges with a cutoff in the past (after the upsert)`() = runTest {
        coEvery { portfolioApi.getPnl(any(), any()) } returns Response.success(pnlFixture)
        val cutoff = slot<Long>()
        val before = System.currentTimeMillis()

        repository.getPnlSummary("portfolio-1", PnlPeriod.ALL)

        coVerify { pnlDao.upsertAndPurge(any(), capture(cutoff)) }
        assertTrue(cutoff.captured < before)
    }

    @Test
    fun `getPnlSummary purge cutoff keeps other periods rows for 24 hours`() = runTest {
        // PR 2.2 open point : une ligne par période — un cutoff de 5 min supprimait la ligne
        // semaine/mois d'une autre instance de widget dès la sync de la période DAY.
        coEvery { portfolioApi.getPnl(any(), any()) } returns Response.success(pnlFixture)
        val cutoff = slot<Long>()
        val before = System.currentTimeMillis()

        repository.getPnlSummary("portfolio-1", PnlPeriod.ALL)
        val after = System.currentTimeMillis()

        coVerify { pnlDao.upsertAndPurge(any(), capture(cutoff)) }
        assertTrue(cutoff.captured >= before - CacheTtl.PNL_RETENTION_MS)
        assertTrue(cutoff.captured <= after - CacheTtl.PNL_RETENTION_MS)
        assertTrue(CacheTtl.PNL_RETENTION_MS >= 24 * 60 * 60 * 1000L)
    }

    // ── getPosition — cache TTL + forceRefresh (#20, B-pm-3) ─────────────────

    private val positionDto = PositionDto(
        id = 42,
        symbol = "TSLA",
        quantity = BigDecimal("10"),
        avgPrice = BigDecimal("250.00"),
        currentPrice = BigDecimal("280.00"),
        isActive = false,
    )

    private fun positionEntity(syncedAt: Long) = PositionEntity(
        id = 42,
        symbol = "TSLA",
        quantity = "10",
        avgPrice = "250.00",
        currentPrice = "270.00",
        unrealizedPnl = null,
        unrealizedPnlPercent = null,
        status = "open",
        openedAt = null,
        syncedAt = syncedAt,
    )

    @Test
    fun `getPosition serves a fresh cache row with its real syncedAt, no network`() = runTest {
        val syncedAt = System.currentTimeMillis() - 30_000L
        coEvery { positionDao.getById(42) } returns positionEntity(syncedAt)

        val cached = repository.getPosition("portfolio-1", 42).getOrThrow()

        assertEquals(BigDecimal("270.00"), cached.value.currentPrice)
        assertEquals(syncedAt, cached.syncedAt)
        coVerify(exactly = 0) { portfolioApi.getPositions(any(), any()) }
    }

    @Test
    fun `getPosition refetches a stale row with status all`() = runTest {
        val stale = System.currentTimeMillis() - CacheTtl.POSITIONS_MS - 1_000L
        coEvery { positionDao.getById(42) } returns positionEntity(stale)
        coEvery { portfolioApi.getPositions(any(), any()) } returns Response.success(listOf(positionDto))
        val before = System.currentTimeMillis()

        val cached = repository.getPosition("portfolio-1", 42).getOrThrow()

        coVerify(exactly = 1) { portfolioApi.getPositions("portfolio-1", "all") }
        assertEquals(BigDecimal("280.00"), cached.value.currentPrice)
        assertTrue(cached.syncedAt >= before)
    }

    @Test
    fun `getPosition resolves a closed position on cache miss`() = runTest {
        coEvery { positionDao.getById(42) } returns null
        coEvery { portfolioApi.getPositions(any(), any()) } returns Response.success(listOf(positionDto))

        val cached = repository.getPosition("portfolio-1", 42).getOrThrow()

        assertEquals(PositionStatus.CLOSED, cached.value.status)
        coVerify(exactly = 1) { portfolioApi.getPositions("portfolio-1", "all") }
    }

    @Test
    fun `getPosition forceRefresh bypasses a fresh cache`() = runTest {
        coEvery { positionDao.getById(42) } returns positionEntity(System.currentTimeMillis())
        coEvery { portfolioApi.getPositions(any(), any()) } returns Response.success(listOf(positionDto))

        repository.getPosition("portfolio-1", 42, forceRefresh = true).getOrThrow()

        coVerify(exactly = 1) { portfolioApi.getPositions("portfolio-1", "all") }
    }

    @Test
    fun `getPosition falls back to the stale row when the network fails`() = runTest {
        val stale = System.currentTimeMillis() - 60 * 60_000L
        coEvery { positionDao.getById(42) } returns positionEntity(stale)
        coEvery { portfolioApi.getPositions(any(), any()) } throws IOException("timeout")

        val cached = repository.getPosition("portfolio-1", 42).getOrThrow()

        assertEquals(stale, cached.syncedAt)
    }

    @Test
    fun `getPosition fails when absent from cache and network`() = runTest {
        coEvery { positionDao.getById(42) } returns null
        coEvery { portfolioApi.getPositions(any(), any()) } returns Response.success(emptyList())

        assertTrue(repository.getPosition("portfolio-1", 42).isFailure)
    }

    @Test
    fun `getPnlSummary YEAR requests the backend ytd period and keys the row on it`() = runTest {
        coEvery { portfolioApi.getPnl(any(), any()) } returns Response.success(pnlFixture.copy(period = "ytd"))
        val entity = slot<PnlSnapshotEntity>()

        repository.getPnlSummary("portfolio-1", PnlPeriod.YEAR)

        coVerify { portfolioApi.getPnl("portfolio-1", "ytd") }
        coVerify { pnlDao.upsertAndPurge(capture(entity), any()) }
        assertEquals("ytd", entity.captured.period)
    }

    @Test
    fun `getPnlSummary ALL does not write Room on HTTP error`() = runTest {
        coEvery { portfolioApi.getPnl(any(), any()) } returns
            Response.error(500, "boom".toResponseBody(null))

        val result = repository.getPnlSummary("portfolio-1", PnlPeriod.ALL)

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { pnlDao.upsertAndPurge(any(), any()) }
    }

    @Test
    fun `getPerformance converts max_drawdown percent to fraction and never writes pnl_snapshots`() = runTest {
        coEvery { portfolioApi.getPerformance(any()) } returns Response.success(performanceFixture)

        val metrics = repository.getPerformance("portfolio-1").getOrThrow()

        assertEquals(0.083, metrics.maxDrawdown!!, 1e-9)
        coVerify(exactly = 0) { pnlDao.upsertAndPurge(any(), any()) }
    }

    // ── getPositions — le backend ignore `status` : filtre côté app (audit compat 29/09) ──

    private fun dto(id: Int, symbol: String, active: Boolean) = PositionDto(
        id = id,
        symbol = symbol,
        quantity = BigDecimal("1"),
        avgPrice = BigDecimal("10.00"),
        currentPrice = BigDecimal("11.00"),
        isActive = active,
    )

    /** Ce que le backend renvoie vraiment, quel que soit `?status=` : actives ET inactives. */
    private val mixedPositions = listOf(dto(1, "SPY", true), dto(2, "QQQ", false), dto(3, "AAPL", true))

    @Test
    fun `getPositions OPEN keeps only active positions even though the backend returns all`() = runTest {
        coEvery { portfolioApi.getPositions(any(), any()) } returns Response.success(mixedPositions)

        val open = repository.getPositions("portfolio-1", PositionStatus.OPEN).getOrThrow()

        assertEquals(listOf("SPY", "AAPL"), open.map { it.symbol })
        assertTrue(open.all { it.status == PositionStatus.OPEN })
    }

    @Test
    fun `getPositions CLOSED keeps only inactive positions`() = runTest {
        coEvery { portfolioApi.getPositions(any(), any()) } returns Response.success(mixedPositions)

        val closed = repository.getPositions("portfolio-1", PositionStatus.CLOSED).getOrThrow()

        assertEquals(listOf("QQQ"), closed.map { it.symbol })
    }

    @Test
    fun `getPositions ALL returns everything`() = runTest {
        coEvery { portfolioApi.getPositions(any(), any()) } returns Response.success(mixedPositions)

        val all = repository.getPositions("portfolio-1", PositionStatus.ALL).getOrThrow()

        assertEquals(listOf("SPY", "QQQ", "AAPL"), all.map { it.symbol })
    }

    @Test
    fun `filtering the list never shrinks what is cached in Room`() = runTest {
        coEvery { portfolioApi.getPositions(any(), any()) } returns Response.success(mixedPositions)
        val cached = slot<List<PositionEntity>>()

        repository.getPositions("portfolio-1", PositionStatus.OPEN).getOrThrow()

        coVerify(exactly = 1) { positionDao.upsertAllAndPurge(capture(cached), any()) }
        assertEquals(3, cached.captured.size) // la position fermée reste résoluble par getPosition()
    }

    // ── getTransactions — le backend ignore `symbol` ──────────────────────────

    private fun tx(id: Long, symbol: String) = TransactionDto(
        id = id,
        symbol = symbol,
        action = "BUY",
        quantity = BigDecimal("1"),
        price = BigDecimal("10.00"),
        commission = BigDecimal("0.10"),
        total = BigDecimal("10.10"),
        executedAt = "2026-09-29T10:00:00+00:00",
    )

    private fun txPage(vararg items: TransactionDto) = Response.success(
        TransactionListResponseDto(transactions = items.toList(), total = items.size, limit = 1000, offset = 0),
    )

    @Test
    fun `getTransactions without a symbol forwards limit and offset untouched`() = runTest {
        coEvery { portfolioApi.getTransactions(any(), any(), any(), any()) } returns
            txPage(tx(1, "SPY"), tx(2, "QQQ"))

        val result = repository.getTransactions("portfolio-1", limit = 50, offset = 100, symbol = null).getOrThrow()

        assertEquals(listOf(1L, 2L), result.map { it.id })
        coVerify(exactly = 1) { portfolioApi.getTransactions("portfolio-1", 50, 100, null) }
    }

    @Test
    fun `getTransactions with a symbol scans the widest page then filters client-side`() = runTest {
        coEvery { portfolioApi.getTransactions(any(), any(), any(), any()) } returns
            txPage(tx(1, "SPY"), tx(2, "QQQ"), tx(3, "spy"), tx(4, "AAPL"), tx(5, "SPY"))

        val result = repository.getTransactions("portfolio-1", limit = 50, offset = 0, symbol = "SPY").getOrThrow()

        assertEquals(listOf(1L, 3L, 5L), result.map { it.id })
        // Fenêtre serveur maximale, offset serveur à 0 : l'offset demandé s'applique APRÈS le filtre.
        coVerify(exactly = 1) { portfolioApi.getTransactions("portfolio-1", 1000, 0, "SPY") }
    }

    @Test
    fun `getTransactions with a symbol applies offset and limit to the filtered list`() = runTest {
        coEvery { portfolioApi.getTransactions(any(), any(), any(), any()) } returns
            txPage(tx(1, "SPY"), tx(2, "QQQ"), tx(3, "SPY"), tx(4, "SPY"), tx(5, "SPY"))

        val page = repository.getTransactions("portfolio-1", limit = 2, offset = 1, symbol = "SPY").getOrThrow()

        assertEquals(listOf(3L, 4L), page.map { it.id })
    }

    @Test
    fun `getTransactions with a symbol that has no trade returns an empty list`() = runTest {
        coEvery { portfolioApi.getTransactions(any(), any(), any(), any()) } returns txPage(tx(1, "SPY"))

        assertTrue(repository.getTransactions("portfolio-1", 50, 0, "TSLA").getOrThrow().isEmpty())
    }
}
