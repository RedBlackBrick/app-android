package com.tradingplatform.app.usecase.portfolio

import com.tradingplatform.app.domain.model.PortfolioBrokerStatus
import com.tradingplatform.app.domain.repository.PortfolioRepository
import com.tradingplatform.app.domain.usecase.portfolio.GetPortfolioBrokerStatusUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GetPortfolioBrokerStatusUseCaseTest {
    private val repository = mockk<PortfolioRepository>()
    private val useCase = GetPortfolioBrokerStatusUseCase(repository)

    @Test
    fun `returns the broker status of the requested portfolio`() = runTest {
        val status = PortfolioBrokerStatus(brokerCode = "alpaca", connectionStatus = "active")
        coEvery { repository.getBrokerStatus("p1") } returns Result.success(status)

        val result = useCase("p1")

        assertEquals(status, result.getOrThrow())
        coVerify(exactly = 1) { repository.getBrokerStatus("p1") }
    }

    @Test
    fun `propagates a repository failure`() = runTest {
        coEvery { repository.getBrokerStatus(any()) } returns Result.failure(RuntimeException("HTTP 403"))

        assertTrue(useCase("p1").isFailure)
    }
}
