package com.tradingplatform.app.usecase.portfolio

import com.tradingplatform.app.domain.model.PortfolioStrategyEntry
import com.tradingplatform.app.domain.repository.StrategiesRepository
import com.tradingplatform.app.domain.usecase.portfolio.GetPortfolioStrategiesUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.io.IOException

class GetPortfolioStrategiesUseCaseTest {
    private val repository = mockk<StrategiesRepository>()
    private lateinit var useCase: GetPortfolioStrategiesUseCase

    @Before
    fun setUp() {
        useCase = GetPortfolioStrategiesUseCase(repository)
    }

    @Test
    fun `returns the named entries of the requested portfolio`() = runTest {
        val entries = listOf(
            PortfolioStrategyEntry(strategyId = "s1", name = "Momentum US", isActive = true),
            PortfolioStrategyEntry(strategyId = "s2", name = null, isActive = false),
        )
        coEvery { repository.listPortfolioStrategyEntries("p1") } returns Result.success(entries)

        val result = useCase("p1")

        assertEquals(entries, result.getOrNull())
        coVerify(exactly = 1) { repository.listPortfolioStrategyEntries("p1") }
    }

    @Test
    fun `propagates a repository failure`() = runTest {
        val failure = IOException("offline")
        coEvery { repository.listPortfolioStrategyEntries("p1") } returns Result.failure(failure)

        val result = useCase("p1")

        assertSame(failure, result.exceptionOrNull())
    }
}
