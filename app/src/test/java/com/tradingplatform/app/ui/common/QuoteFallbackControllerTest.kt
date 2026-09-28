package com.tradingplatform.app.ui.common

import app.cash.turbine.test
import com.tradingplatform.app.domain.model.Quote
import com.tradingplatform.app.domain.model.WsConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant

private fun quote(price: String, source: String) = Quote(
    symbol = "AAPL",
    price = BigDecimal(price),
    bid = null,
    ask = null,
    volume = 0L,
    change = null,
    changePercent = null,
    timestamp = Instant.parse("2026-09-28T10:00:00Z"),
    source = source,
)

/**
 * PR 3.4 (audit #13/#14, plan-market-data §D) — REST fallback driven by the public WS
 * connection state. `runTest` uses a [kotlinx.coroutines.test.StandardTestDispatcher]:
 * virtual time only moves with [advanceTimeBy] / [runCurrent].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuoteFallbackControllerTest {

    private val restQuote = quote("175.50", "rest")

    /** Test harness: every callback is recorded as an [Event] on a channel read with Turbine. */
    private sealed interface Event {
        data class QuoteReceived(val symbol: String, val quote: Quote) : Event
        data class Stale(val symbol: String) : Event
        data class FetchError(val symbol: String, val error: Throwable) : Event
    }

    private class Harness(scope: CoroutineScope, initialState: WsConnectionState, foreground: Boolean) {
        val state = MutableStateFlow(initialState)
        val foreground = MutableStateFlow(foreground)
        val ws = MutableSharedFlow<Quote>()
        val events = Channel<Event>(Channel.UNLIMITED)
        var fetchCount = 0
        var fetchResult: Result<Quote> = Result.success(quote("175.50", "rest"))

        val controller = QuoteFallbackController(
            scope = scope,
            connectionState = state,
            isForeground = this.foreground,
            stream = { ws },
            fetch = { fetchCount++; fetchResult },
            onQuote = { s, q -> events.trySend(Event.QuoteReceived(s, q)) },
            onStale = { s -> events.trySend(Event.Stale(s)) },
            onFetchError = { s, e -> events.trySend(Event.FetchError(s, e)) },
        )
    }

    private fun TestScope.harness(
        initialState: WsConnectionState,
        foreground: Boolean = true,
    ): Harness = Harness(backgroundScope, initialState, foreground)

    @Test
    fun `Disconnected triggers onStale then REST fetch every 30s`() = runTest {
        val h = harness(WsConnectionState.Disconnected)
        h.controller.watch("AAPL")

        h.events.receiveAsFlow().test {
            // Debounce 2 s : rien avant
            advanceTimeBy(1_999L)
            runCurrent()
            expectNoEvents()
            assertEquals(0, h.fetchCount)

            advanceTimeBy(2L)
            assertEquals(Event.Stale("AAPL"), awaitItem())
            assertEquals(Event.QuoteReceived("AAPL", restQuote), awaitItem())
            assertEquals(1, h.fetchCount)

            advanceTimeBy(30_000L)
            assertEquals(Event.QuoteReceived("AAPL", restQuote), awaitItem())
            assertEquals(2, h.fetchCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `REST fetch failure is reported through onFetchError and polling continues`() = runTest {
        val h = harness(WsConnectionState.Disconnected)
        val error = IOException("timeout")
        h.fetchResult = Result.failure(error)
        h.controller.watch("AAPL")

        h.events.receiveAsFlow().test {
            advanceTimeBy(2_001L)
            assertEquals(Event.Stale("AAPL"), awaitItem())
            assertEquals(Event.FetchError("AAPL", error), awaitItem())
            advanceTimeBy(30_000L)
            assertEquals(Event.FetchError("AAPL", error), awaitItem())
            assertEquals(2, h.fetchCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Connected stops REST polling`() = runTest {
        val h = harness(WsConnectionState.Disconnected)
        h.controller.watch("AAPL")

        advanceTimeBy(2_001L)
        assertEquals(1, h.fetchCount)

        h.state.value = WsConnectionState.Connected
        runCurrent()
        advanceTimeBy(300_000L)
        assertEquals(1, h.fetchCount)
    }

    @Test
    fun `no polling and no stale while Connected, flaps shorter than the debounce are ignored`() = runTest {
        val h = harness(WsConnectionState.Connected)
        h.controller.watch("AAPL")

        h.events.receiveAsFlow().test {
            runCurrent()
            h.state.value = WsConnectionState.Connecting
            advanceTimeBy(1_500L)
            h.state.value = WsConnectionState.Connected
            advanceTimeBy(120_000L)
            expectNoEvents()
            assertEquals(0, h.fetchCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `WS quote delivered after reconnection without resubscribing`() = runTest {
        val h = harness(WsConnectionState.Connected)
        h.controller.watch("AAPL")
        runCurrent()

        h.events.receiveAsFlow().test {
            val first = quote("170.00", "ws_public")
            h.ws.emit(first)
            assertEquals(Event.QuoteReceived("AAPL", first), awaitItem())

            // Coupure durable → Stale + fallback REST
            h.state.value = WsConnectionState.Disconnected
            advanceTimeBy(2_001L)
            assertEquals(Event.Stale("AAPL"), awaitItem())
            assertEquals(Event.QuoteReceived("AAPL", restQuote), awaitItem())

            // Reconnexion : le flux WS n'a jamais été annulé → le cours suivant arrive
            h.state.value = WsConnectionState.Connected
            runCurrent()
            val second = quote("181.00", "ws_public")
            h.ws.emit(second)
            assertEquals(Event.QuoteReceived("AAPL", second), awaitItem())

            // … et le polling est arrêté
            advanceTimeBy(120_000L)
            expectNoEvents()
            assertEquals(1, h.fetchCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Degraded is treated as not live`() = runTest {
        val h = harness(WsConnectionState.Degraded)
        h.controller.watch("AAPL")
        advanceTimeBy(2_001L)
        assertEquals(1, h.fetchCount)
    }

    @Test
    fun `background marks stale but does not poll, foreground resumes polling`() = runTest {
        val h = harness(WsConnectionState.Disconnected, foreground = false)
        h.controller.watch("AAPL")

        h.events.receiveAsFlow().test {
            advanceTimeBy(2_001L)
            assertEquals(Event.Stale("AAPL"), awaitItem())
            advanceTimeBy(120_000L)
            expectNoEvents()
            assertEquals(0, h.fetchCount)

            h.foreground.value = true
            runCurrent()
            assertEquals(Event.Stale("AAPL"), awaitItem())
            assertEquals(Event.QuoteReceived("AAPL", restQuote), awaitItem())
            assertEquals(1, h.fetchCount)

            // Retour en arrière-plan → polling annulé
            h.foreground.value = false
            runCurrent()
            advanceTimeBy(120_000L)
            assertEquals(1, h.fetchCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `cancelling the watch job stops polling`() = runTest {
        val h = harness(WsConnectionState.Disconnected)
        val job = h.controller.watch("AAPL")
        advanceTimeBy(2_001L)
        assertEquals(1, h.fetchCount)

        job.cancel()
        advanceTimeBy(300_000L)
        assertEquals(1, h.fetchCount)
        assertTrue(job.isCancelled)
    }
}
