package com.tradingplatform.app.usecase.portfolio

import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.PnlSummary
import com.tradingplatform.app.domain.repository.PortfolioRepository
import com.tradingplatform.app.domain.usecase.portfolio.GetPnlUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

class GetPnlUseCaseTest {
    private val repository = mockk<PortfolioRepository>()
    private lateinit var useCase: GetPnlUseCase

    private val fakePnl = PnlSummary(
        totalReturn = BigDecimal("1500.00"),
        totalReturnPct = 0.035,
        sharpeRatio = 1.1,
        sortinoRatio = 1.4,
        maxDrawdown = -0.04,
        volatility = 0.10,
        cagr = 0.07,
        winRate = 0.7,
        profitFactor = 2.1,
        avgTradeReturn = BigDecimal("150.00"),
    )

    @Before
    fun setUp() {
        useCase = GetPnlUseCase(repository)
    }

    @Test
    fun `returns PnlSummary on success`() = runTest {
        coEvery { repository.getPnl("1", PnlPeriod.DAY) } returns Result.success(fakePnl)

        val result = useCase("1")

        assertTrue(result.isSuccess)
        assertEquals(BigDecimal("1500.00"), result.getOrThrow().totalReturn)
    }

    @Test
    fun `defaults to DAY period`() = runTest {
        coEvery { repository.getPnl("1", PnlPeriod.DAY) } returns Result.success(fakePnl)

        useCase("1")

        coVerify { repository.getPnl("1", PnlPeriod.DAY) }
    }

    @Test
    fun `passes explicit period to repository`() = runTest {
        coEvery { repository.getPnl("1", PnlPeriod.MONTH) } returns Result.success(fakePnl)

        useCase("1", PnlPeriod.MONTH)

        coVerify { repository.getPnl("1", PnlPeriod.MONTH) }
    }

    @Test
    fun `returns failure on repository error`() = runTest {
        coEvery { repository.getPnl(any(), any()) } returns Result.failure(RuntimeException("Timeout"))

        val result = useCase("1")

        assertTrue(result.isFailure)
    }
}
