package com.tradingplatform.app.usecase.risk

import com.tradingplatform.app.domain.model.RiskStatus
import com.tradingplatform.app.domain.repository.RiskRepository
import com.tradingplatform.app.domain.usecase.risk.GetRiskStatusUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GetRiskStatusUseCaseTest {
    private val repository = mockk<RiskRepository>()
    private val useCase = GetRiskStatusUseCase(repository)

    private val status = RiskStatus(
        killSwitchActive = true,
        killSwitchReason = "Drawdown limit breached",
        unresolvedViolations = 2,
        dailyLossUsagePct = 0.5,
        drawdownCurrentPct = -0.124,
    )

    @Test
    fun `returns the status of the requested portfolio`() = runTest {
        coEvery { repository.getRiskStatus("p-1") } returns Result.success(status)

        assertEquals(status, useCase("p-1").getOrThrow())
        coVerify(exactly = 1) { repository.getRiskStatus("p-1") }
    }

    @Test
    fun `propagates a repository failure`() = runTest {
        coEvery { repository.getRiskStatus(any()) } returns Result.failure(RuntimeException("HTTP 500"))

        assertTrue(useCase("p-1").isFailure)
    }
}
