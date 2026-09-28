package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.PortfolioApi
import com.tradingplatform.app.data.local.db.dao.PnlDao
import com.tradingplatform.app.data.local.db.dao.PositionDao
import com.tradingplatform.app.data.model.PerformanceResponseDto
import com.tradingplatform.app.data.model.PnlResponseDto
import com.tradingplatform.app.domain.model.PnlPeriod
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.math.BigDecimal

/**
 * Audit finding #4 (NEW-pnlwidget-1) — the PnL widget reads `pnl_snapshots`
 * (`PnlWidget` → `pnlDao.getLatestByPeriod`), but the path the worker calls
 * (`GetPnlUseCase` → `getPnlSummary`) never writes Room. The only writer is `getPnl`,
 * which has no call site in app/src/main.
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
        totalPnlPercent = 1.25,
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
        // Expected RED on current code: getPnlSummary only maps the DTO, no Room write.
        coEvery { portfolioApi.getPnl(any(), any()) } returns Response.success(pnlFixture)

        val result = repository.getPnlSummary("portfolio-1", PnlPeriod.DAY)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { pnlDao.upsertAndPurge(any(), any()) }
    }

    @Test
    fun `getPnl writes pnl_snapshots (currently the only writer, with no caller in main)`() = runTest {
        // Documents the dead path: GREEN today. After the fix either this method is removed
        // (delete this test) or it becomes the one the worker calls.
        coEvery { portfolioApi.getPerformance(any()) } returns Response.success(performanceFixture)

        val result = repository.getPnl("portfolio-1", PnlPeriod.DAY)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { pnlDao.upsertAndPurge(any(), any()) }
    }
}
