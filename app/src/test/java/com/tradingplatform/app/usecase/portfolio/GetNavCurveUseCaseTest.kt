package com.tradingplatform.app.usecase.portfolio

import com.tradingplatform.app.domain.model.NavCurve
import com.tradingplatform.app.domain.model.NavPoint
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.repository.PortfolioRepository
import com.tradingplatform.app.domain.usecase.portfolio.GetNavCurveUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class GetNavCurveUseCaseTest {
    private val repository = mockk<PortfolioRepository>()
    private val useCase = GetNavCurveUseCase(repository)

    private val curve = NavCurve(
        listOf(
            NavPoint(Instant.parse("2026-09-28T10:00:00Z"), BigDecimal("10000.00")),
            NavPoint(Instant.parse("2026-09-29T10:00:00Z"), BigDecimal("10074.00")),
        ),
    )

    @Test
    fun `returns the curve for the requested portfolio and period`() = runTest {
        coEvery { repository.getNavCurve("p1", PnlPeriod.MONTH) } returns Result.success(curve)

        val result = useCase("p1", PnlPeriod.MONTH)

        assertEquals(curve, result.getOrThrow())
        coVerify(exactly = 1) { repository.getNavCurve("p1", PnlPeriod.MONTH) }
    }

    @Test
    fun `defaults to the DAY period`() = runTest {
        coEvery { repository.getNavCurve("p1", PnlPeriod.DAY) } returns Result.success(NavCurve(emptyList()))

        useCase("p1")

        coVerify(exactly = 1) { repository.getNavCurve("p1", PnlPeriod.DAY) }
    }

    @Test
    fun `propagates a repository failure`() = runTest {
        coEvery { repository.getNavCurve(any(), any()) } returns Result.failure(RuntimeException("HTTP 404"))

        assertTrue(useCase("p1", PnlPeriod.ALL).isFailure)
    }
}
