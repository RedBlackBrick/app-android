package com.tradingplatform.app.usecase.portfolio

import com.tradingplatform.app.domain.repository.PortfolioSelectionRepository
import com.tradingplatform.app.domain.usecase.portfolio.SelectPortfolioUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectPortfolioUseCaseTest {

    private val repo = mockk<PortfolioSelectionRepository>()
    private val useCase = SelectPortfolioUseCase(repo)

    @Test
    fun `selects the requested portfolio through the repository`() = runTest {
        coEvery { repo.select("B") } returns Result.success(Unit)

        val result = useCase("B")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { repo.select("B") }
    }

    @Test
    fun `propagates the repository failure`() = runTest {
        coEvery { repo.select("Z") } returns Result.failure(IllegalStateException("Unknown portfolio"))

        val result = useCase("Z")

        assertEquals("Unknown portfolio", result.exceptionOrNull()?.message)
    }
}
