package com.tradingplatform.app.usecase.portfolio

import com.tradingplatform.app.domain.model.Position
import com.tradingplatform.app.domain.model.PositionStatus
import com.tradingplatform.app.domain.repository.PortfolioRepository
import com.tradingplatform.app.domain.usecase.portfolio.GetPositionsUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class GetPositionsUseCaseTest {
    private val repository = mockk<PortfolioRepository>()
    private lateinit var useCase: GetPositionsUseCase

    private val fakePosition = Position(
        id = 1,
        symbol = "AAPL",
        quantity = BigDecimal("100"),
        avgPrice = BigDecimal("150.00"),
        currentPrice = BigDecimal("160.00"),
        unrealizedPnl = BigDecimal("1000.00"),
        unrealizedPnlPercent = 6.67,
        status = PositionStatus.OPEN,
        openedAt = Instant.now(),
    )

    @Before
    fun setUp() {
        useCase = GetPositionsUseCase(repository)
    }

    @Test
    fun `returns positions on success`() = runTest {
        coEvery { repository.getPositions("1", PositionStatus.OPEN) } returns Result.success(listOf(fakePosition))

        val result = useCase("1")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isNotEmpty())
        assertEquals("AAPL", result.getOrThrow()[0].symbol)
    }

    @Test
    fun `returns failure on repository error`() = runTest {
        coEvery { repository.getPositions(any(), any()) } returns Result.failure(RuntimeException("Network error"))

        val result = useCase("1")

        assertTrue(result.isFailure)
    }

    @Test
    fun `defaults to OPEN status`() = runTest {
        coEvery { repository.getPositions("1", PositionStatus.OPEN) } returns Result.success(listOf(fakePosition))

        useCase("1") // no explicit status

        coVerify { repository.getPositions("1", PositionStatus.OPEN) }
    }

    @Test
    fun `passes explicit status to repository`() = runTest {
        coEvery { repository.getPositions("1", PositionStatus.CLOSED) } returns Result.success(emptyList())

        val result = useCase("1", PositionStatus.CLOSED)

        assertTrue(result.isSuccess)
        coVerify { repository.getPositions("1", PositionStatus.CLOSED) }
    }
}
