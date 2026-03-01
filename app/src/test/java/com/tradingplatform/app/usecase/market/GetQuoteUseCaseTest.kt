package com.tradingplatform.app.usecase.market

import com.tradingplatform.app.domain.model.Quote
import com.tradingplatform.app.domain.repository.MarketDataRepository
import com.tradingplatform.app.domain.usecase.market.GetQuoteUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class GetQuoteUseCaseTest {
    private val repository = mockk<MarketDataRepository>()
    private lateinit var useCase: GetQuoteUseCase

    private val fakeQuote = Quote(
        symbol = "AAPL",
        price = BigDecimal("160.00"),
        bid = BigDecimal("159.98"),
        ask = BigDecimal("160.02"),
        volume = 45_231_000L,
        change = BigDecimal("2.50"),
        changePercent = 1.59,
        timestamp = Instant.now(),
        source = "yahoo",
    )

    @Before
    fun setUp() {
        useCase = GetQuoteUseCase(repository)
    }

    @Test
    fun `returns quote on success`() = runTest {
        coEvery { repository.getQuote("AAPL") } returns Result.success(fakeQuote)

        val result = useCase("AAPL")

        assertTrue(result.isSuccess)
        assertEquals(BigDecimal("160.00"), result.getOrThrow().price)
        assertEquals("AAPL", result.getOrThrow().symbol)
    }

    @Test
    fun `returns failure on error`() = runTest {
        coEvery { repository.getQuote(any()) } returns Result.failure(RuntimeException("Timeout"))

        val result = useCase("AAPL")

        assertTrue(result.isFailure)
    }

    @Test
    fun `passes symbol to repository`() = runTest {
        coEvery { repository.getQuote("TSLA") } returns Result.success(fakeQuote.copy(symbol = "TSLA"))

        val result = useCase("TSLA")

        assertTrue(result.isSuccess)
    }
}
