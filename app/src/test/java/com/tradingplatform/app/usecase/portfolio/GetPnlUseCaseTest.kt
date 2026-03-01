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
        realizedPnl = BigDecimal("500.00"),
        unrealizedPnl = BigDecimal("1000.00"),
        totalPnl = BigDecimal("1500.00"),
        totalPnlPercent = 3.5,
        tradesCount = 10,
        winningTrades = 7,
        losingTrades = 3,
    )

    @Before
    fun setUp() {
        useCase = GetPnlUseCase(repository)
    }

    @Test
    fun `returns PnlSummary on success`() = runTest {
        coEvery { repository.getPnl(1, PnlPeriod.DAY) } returns Result.success(fakePnl)

        val result = useCase(1)

        assertTrue(result.isSuccess)
        assertEquals(BigDecimal("1500.00"), result.getOrThrow().totalPnl)
    }

    @Test
    fun `defaults to DAY period`() = runTest {
        coEvery { repository.getPnl(1, PnlPeriod.DAY) } returns Result.success(fakePnl)

        useCase(1)

        coVerify { repository.getPnl(1, PnlPeriod.DAY) }
    }

    @Test
    fun `passes explicit period to repository`() = runTest {
        coEvery { repository.getPnl(1, PnlPeriod.MONTH) } returns Result.success(fakePnl)

        useCase(1, PnlPeriod.MONTH)

        coVerify { repository.getPnl(1, PnlPeriod.MONTH) }
    }

    @Test
    fun `returns failure on repository error`() = runTest {
        coEvery { repository.getPnl(any(), any()) } returns Result.failure(RuntimeException("Timeout"))

        val result = useCase(1)

        assertTrue(result.isFailure)
    }
}
