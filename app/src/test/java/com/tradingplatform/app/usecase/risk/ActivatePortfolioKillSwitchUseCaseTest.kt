package com.tradingplatform.app.usecase.risk

import com.tradingplatform.app.domain.model.WriteOutcome
import com.tradingplatform.app.domain.repository.RiskRepository
import com.tradingplatform.app.domain.usecase.risk.ActivatePortfolioKillSwitchUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivatePortfolioKillSwitchUseCaseTest {
    private val repository = mockk<RiskRepository>()
    private val useCase = ActivatePortfolioKillSwitchUseCase(repository)

    @Test
    fun `activates with the trimmed reason and returns the outcome`() = runTest {
        coEvery { repository.activatePortfolioKillSwitch("p-1", "Arrêt manuel") } returns
            Result.success(WriteOutcome.CONFIRMED)

        val result = useCase("p-1", "  Arrêt manuel  ")

        assertEquals(WriteOutcome.CONFIRMED, result.getOrThrow())
        coVerify(exactly = 1) { repository.activatePortfolioKillSwitch("p-1", "Arrêt manuel") }
    }

    @Test
    fun `passes REQUESTED_UNCONFIRMED through without retrying`() = runTest {
        coEvery { repository.activatePortfolioKillSwitch(any(), any()) } returns
            Result.success(WriteOutcome.REQUESTED_UNCONFIRMED)

        assertEquals(WriteOutcome.REQUESTED_UNCONFIRMED, useCase("p-1", "Arrêt").getOrThrow())
        coVerify(exactly = 1) { repository.activatePortfolioKillSwitch(any(), any()) }
    }

    @Test
    fun `rejects an empty reason before any repository call`() = runTest {
        val result = useCase("p-1", "")

        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        coVerify(exactly = 0) { repository.activatePortfolioKillSwitch(any(), any()) }
    }

    @Test
    fun `rejects a blank reason before any repository call`() = runTest {
        val result = useCase("p-1", "  \n ")

        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        coVerify(exactly = 0) { repository.activatePortfolioKillSwitch(any(), any()) }
    }

    @Test
    fun `rejects a reason longer than 500 characters before any repository call`() = runTest {
        val result = useCase("p-1", "x".repeat(501))

        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        coVerify(exactly = 0) { repository.activatePortfolioKillSwitch(any(), any()) }
    }

    @Test
    fun `accepts a reason of exactly 500 characters`() = runTest {
        val reason = "x".repeat(500)
        coEvery { repository.activatePortfolioKillSwitch("p-1", reason) } returns
            Result.success(WriteOutcome.CONFIRMED)

        assertEquals(WriteOutcome.CONFIRMED, useCase("p-1", reason).getOrThrow())
    }

    @Test
    fun `propagates a repository failure`() = runTest {
        coEvery { repository.activatePortfolioKillSwitch(any(), any()) } returns
            Result.failure(RuntimeException("HTTP 403"))

        assertTrue(useCase("p-1", "Arrêt").isFailure)
    }
}
