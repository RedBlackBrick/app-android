package com.tradingplatform.app.usecase.portfolio

import com.tradingplatform.app.domain.exception.HttpStatusException
import com.tradingplatform.app.domain.model.WriteOutcome
import com.tradingplatform.app.domain.repository.StrategiesRepository
import com.tradingplatform.app.domain.usecase.portfolio.SetPortfolioStrategyActiveUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class SetPortfolioStrategyActiveUseCaseTest {
    private val repository = mockk<StrategiesRepository>()
    private lateinit var useCase: SetPortfolioStrategyActiveUseCase

    @Before
    fun setUp() {
        useCase = SetPortfolioStrategyActiveUseCase(repository)
    }

    @Test
    fun `pauses the link of the given portfolio and strategy`() = runTest {
        coEvery { repository.setPortfolioStrategyActive("p1", "s1", false) } returns
            Result.success(WriteOutcome.CONFIRMED)

        val result = useCase("p1", "s1", active = false)

        assertEquals(WriteOutcome.CONFIRMED, result.getOrNull())
        coVerify(exactly = 1) { repository.setPortfolioStrategyActive("p1", "s1", false) }
    }

    @Test
    fun `resumes the link and propagates an unconfirmed outcome`() = runTest {
        coEvery { repository.setPortfolioStrategyActive("p1", "s2", true) } returns
            Result.success(WriteOutcome.REQUESTED_UNCONFIRMED)

        val result = useCase("p1", "s2", active = true)

        assertEquals(WriteOutcome.REQUESTED_UNCONFIRMED, result.getOrNull())
        coVerify(exactly = 1) { repository.setPortfolioStrategyActive("p1", "s2", true) }
    }

    @Test
    fun `propagates a failure without retrying`() = runTest {
        val forbidden = HttpStatusException(403, "v1/portfolios/{portfolio_id}/strategies/{strategy_id}")
        coEvery { repository.setPortfolioStrategyActive("p1", "s1", false) } returns Result.failure(forbidden)

        val result = useCase("p1", "s1", active = false)

        assertSame(forbidden, result.exceptionOrNull())
        coVerify(exactly = 1) { repository.setPortfolioStrategyActive("p1", "s1", false) }
    }
}
