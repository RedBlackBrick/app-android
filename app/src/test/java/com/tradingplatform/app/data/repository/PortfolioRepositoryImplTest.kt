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
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.PositionStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
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
 */
class PortfolioRepositoryImplTest {

    private val portfolioApi = mockk<PortfolioApi>()
    private val positionDao = mockk<PositionDao>(relaxed = true)
    private val pnlDao = mockk<PnlDao>(relaxed = true)

    private val repository = PortfolioRepositoryImpl(portfolioApi, positionDao, pnlDao)

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
    fun `getPnlSummary persists the snapshot read by the PnL widget`() = runTest {
        coEvery { portfolioApi.getPnl(any(), any()) } returns Response.success(pnlFixture)
        val entity = slot<PnlSnapshotEntity>()

        val result = repository.getPnlSummary("portfolio-1", PnlPeriod.DAY)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { pnlDao.upsertAndPurge(capture(entity), any()) }
        assertEquals("day", entity.captured.period)
        assertEquals("100.25", entity.captured.totalPnl)
        assertEquals(0.0175, entity.captured.totalPnlPercent, 1e-9)
        assertEquals(4, entity.captured.tradesCount)
    }

    @Test
    fun `getPnlSummary returns the domain summary with percent converted to fraction`() = runTest {
        coEvery { portfolioApi.getPnl(any(), any()) } returns Response.success(pnlFixture)

        val summary = repository.getPnlSummary("portfolio-1", PnlPeriod.DAY).getOrThrow()

        assertEquals(BigDecimal("100.25"), summary.totalReturn)
        assertEquals(0.0175, summary.totalReturnPct!!, 1e-9)
        assertEquals(0.75, summary.winRate!!, 1e-9)
    }

    @Test
    fun `getPnlSummary purges with a cutoff in the past (after the upsert)`() = runTest {
        coEvery { portfolioApi.getPnl(any(), any()) } returns Response.success(pnlFixture)
        val cutoff = slot<Long>()
        val before = System.currentTimeMillis()

        repository.getPnlSummary("portfolio-1", PnlPeriod.DAY)

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

        repository.getPnlSummary("portfolio-1", PnlPeriod.DAY)
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
    fun `getPnlSummary does not write Room on HTTP error`() = runTest {
        coEvery { portfolioApi.getPnl(any(), any()) } returns
            Response.error(500, "boom".toResponseBody(null))

        val result = repository.getPnlSummary("portfolio-1", PnlPeriod.DAY)

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
}
