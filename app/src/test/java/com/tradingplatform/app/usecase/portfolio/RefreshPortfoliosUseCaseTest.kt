package com.tradingplatform.app.usecase.portfolio

import com.tradingplatform.app.domain.model.Portfolio
import com.tradingplatform.app.domain.repository.PortfolioSelectionRepository
import com.tradingplatform.app.domain.usecase.portfolio.RefreshPortfoliosUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshPortfoliosUseCaseTest {

    private val repo = mockk<PortfolioSelectionRepository>()
    private val useCase = RefreshPortfoliosUseCase(repo)

    @Test
    fun `returns the portfolios refreshed by the repository`() = runTest {
        val portfolios = listOf(
            Portfolio(id = "A", name = "Alpha", currency = "EUR"),
            Portfolio(id = "B", name = "Beta", currency = "USD"),
        )
        coEvery { repo.refresh() } returns Result.success(portfolios)

        val result = useCase()

        assertEquals(portfolios, result.getOrThrow())
        coVerify(exactly = 1) { repo.refresh() }
    }

    @Test
    fun `propagates the No portfolio found failure`() = runTest {
        coEvery { repo.refresh() } returns Result.failure(IllegalStateException("No portfolio found"))

        val result = useCase()

        assertEquals("No portfolio found", result.exceptionOrNull()?.message)
    }
}
