package com.tradingplatform.app.usecase.portfolio

import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.PortfolioOverviewItem
import com.tradingplatform.app.domain.repository.PortfolioRepository
import com.tradingplatform.app.domain.usecase.portfolio.GetPortfoliosOverviewUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class GetPortfoliosOverviewUseCaseTest {
    private val repository = mockk<PortfolioRepository>()
    private val useCase = GetPortfoliosOverviewUseCase(repository)

    private val item = PortfolioOverviewItem(
        portfolioId = "p1",
        name = "Growth EUR",
        currency = "EUR",
        currentValue = BigDecimal("10074.00"),
        periodPnl = BigDecimal("-84.31"),
        periodPnlPct = -0.00826,
    )

    @Test
    fun `returns the overview and forwards the requested period`() = runTest {
        coEvery { repository.getPortfoliosOverview(PnlPeriod.WEEK) } returns Result.success(listOf(item))

        val result = useCase(PnlPeriod.WEEK)

        assertEquals(listOf(item), result.getOrThrow())
        coVerify(exactly = 1) { repository.getPortfoliosOverview(PnlPeriod.WEEK) }
    }

    @Test
    fun `defaults to the DAY period`() = runTest {
        coEvery { repository.getPortfoliosOverview(PnlPeriod.DAY) } returns Result.success(emptyList())

        useCase()

        coVerify(exactly = 1) { repository.getPortfoliosOverview(PnlPeriod.DAY) }
    }

    @Test
    fun `propagates a repository failure`() = runTest {
        coEvery { repository.getPortfoliosOverview(any()) } returns Result.failure(RuntimeException("Timeout"))

        assertTrue(useCase(PnlPeriod.MONTH).isFailure)
    }
}
