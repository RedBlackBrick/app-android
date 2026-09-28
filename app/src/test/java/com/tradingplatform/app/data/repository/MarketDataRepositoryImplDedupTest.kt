package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.MarketDataApi
import com.tradingplatform.app.data.local.db.dao.QuoteDao
import com.tradingplatform.app.data.model.QuoteDto
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger

/**
 * PR 3.3 (audit #10 / B-auth-conc-1 + B-misc-1) — [MarketDataRepositoryImpl.getQuote] must
 * dedup concurrent in-flight requests for the same symbol via a [kotlinx.coroutines.Deferred]
 * that runs in the injected application [kotlinx.coroutines.CoroutineScope] (here:
 * [kotlinx.coroutines.test.TestScope.backgroundScope]), detached from any individual caller's
 * coroutine — so cancelling one caller must never cancel the shared fetch for the others.
 *
 * This replaces the old `supervisorScope`-based dedup, which parented the fetch to the
 * *winning* caller's own job: if that caller was cancelled first, the shared fetch (and thus
 * every other awaiting caller) died with it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MarketDataRepositoryImplDedupTest {

    private val api = mockk<MarketDataApi>()
    private val quoteDao = mockk<QuoteDao>(relaxed = true)

    private fun fakeQuoteDto(symbol: String) = QuoteDto(
        symbol = symbol,
        price = BigDecimal("175.50"),
        bid = BigDecimal("175.48"),
        ask = BigDecimal("175.52"),
        volume = 35_000_000L,
        change = BigDecimal("2.30"),
        changePercent = 1.33,
        timestamp = "2026-09-28T10:00:00Z",
        source = "yahoo",
    )

    @Test
    fun `cancelling the first caller does not cancel the shared fetch — the second caller still succeeds with a single API call`() =
        runTest {
            val callCount = AtomicInteger(0)
            val releaseGate = CompletableDeferred<Unit>()

            coEvery { api.getQuote("AAPL") } coAnswers {
                callCount.incrementAndGet()
                releaseGate.await()
                Response.success(fakeQuoteDto("AAPL"))
            }

            val repository = MarketDataRepositoryImpl(api, quoteDao, backgroundScope)

            // First caller — becomes the "winner" that starts the shared fetch.
            val caller1 = launch { repository.getQuote("AAPL") }
            // Second caller — must dedup onto the same in-flight fetch as caller1.
            val caller2 = async { repository.getQuote("AAPL") }

            // Let both callers reach the network call / the shared Deferred's await().
            runCurrent()
            assertEquals("API must be called exactly once for two concurrent callers", 1, callCount.get())

            // Cancel the winning caller while the fetch is still in flight.
            caller1.cancel()
            runCurrent()
            assertTrue("caller1 must actually be cancelled", caller1.isCancelled)

            // Unblock the (still running) shared fetch — it must not have been torn down by
            // caller1's cancellation.
            releaseGate.complete(Unit)
            advanceUntilIdle()

            val result2 = caller2.await()
            assertTrue("caller2 must still receive a successful result: ${result2.exceptionOrNull()}", result2.isSuccess)
            assertEquals("AAPL", result2.getOrNull()?.symbol)
            assertEquals("Only one network call must have been made for the whole dedup window", 1, callCount.get())
        }

    @Test
    fun `a later call after the in-flight fetch completes triggers a brand new fetch`() = runTest {
        val callCount = AtomicInteger(0)

        coEvery { api.getQuote("AAPL") } coAnswers {
            callCount.incrementAndGet()
            Response.success(fakeQuoteDto("AAPL"))
        }

        val repository = MarketDataRepositoryImpl(api, quoteDao, backgroundScope)

        val first = repository.getQuote("AAPL")
        advanceUntilIdle()
        assertTrue(first.isSuccess)
        assertEquals(1, callCount.get())

        // The in-flight entry must have been removed on completion (invokeOnCompletion) — a
        // later call is a brand new fetch, not a stale dedup hit.
        val second = repository.getQuote("AAPL")
        advanceUntilIdle()
        assertTrue(second.isSuccess)
        assertEquals(2, callCount.get())
    }
}
