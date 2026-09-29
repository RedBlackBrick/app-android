package com.tradingplatform.app.ui.screens.market

import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.model.Quote
import com.tradingplatform.app.domain.model.SymbolInfo
import com.tradingplatform.app.domain.model.SymbolPage
import com.tradingplatform.app.domain.model.WsConnectionState
import com.tradingplatform.app.domain.usecase.market.AddToWatchlistUseCase
import com.tradingplatform.app.domain.usecase.market.GetAvailableSymbolsUseCase
import com.tradingplatform.app.domain.usecase.market.GetPublicWsConnectionStateUseCase
import com.tradingplatform.app.domain.usecase.market.GetQuoteStreamUseCase
import com.tradingplatform.app.domain.usecase.market.GetQuoteUseCase
import com.tradingplatform.app.domain.usecase.market.GetSymbolHistoryUseCase
import com.tradingplatform.app.domain.usecase.market.GetWatchlistUseCase
import com.tradingplatform.app.domain.usecase.market.RemoveFromWatchlistUseCase
import com.tradingplatform.app.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant

/**
 * PR 2.1 (audit #6) — server-side search (debounced) and offset pagination for the
 * watchlist symbol picker (`MarketDataViewModel.symbolPickerState`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MarketDataViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val getWatchlistUseCase = mockk<GetWatchlistUseCase>()
    private val getQuoteStreamUseCase = mockk<GetQuoteStreamUseCase>()
    private val getQuoteUseCase = mockk<GetQuoteUseCase>()
    private val addToWatchlistUseCase = mockk<AddToWatchlistUseCase>()
    private val removeFromWatchlistUseCase = mockk<RemoveFromWatchlistUseCase>()
    private val getAvailableSymbolsUseCase = mockk<GetAvailableSymbolsUseCase>()
    private val getSymbolHistoryUseCase = mockk<GetSymbolHistoryUseCase>()
    private val getPublicWsConnectionStateUseCase = mockk<GetPublicWsConnectionStateUseCase>()

    /** État du WS public — Connected par défaut (pas de fallback REST dans les tests picker). */
    private val publicWsState = MutableStateFlow(WsConnectionState.Connected)
    private val appForeground = MutableStateFlow(true)

    private lateinit var viewModel: MarketDataViewModel

    private val fakeQuote = Quote(
        symbol = "AAPL",
        price = BigDecimal("175.50"),
        bid = BigDecimal("175.48"),
        ask = BigDecimal("175.52"),
        volume = 35_000_000L,
        change = BigDecimal("2.30"),
        changePercent = 1.33,
        timestamp = Instant.parse("2026-09-28T10:00:00Z"),
        source = "yahoo",
    )

    @Before
    fun setUp() {
        every { getWatchlistUseCase() } returns emptyFlow()
        every { getQuoteStreamUseCase(any()) } returns emptyFlow()
        coEvery { getSymbolHistoryUseCase(any(), any()) } returns Result.success(emptyList())
        every { getPublicWsConnectionStateUseCase() } returns publicWsState
        every { getPublicWsConnectionStateUseCase.isAppForeground() } returns appForeground
    }

    private fun createViewModel(): MarketDataViewModel = MarketDataViewModel(
        getWatchlistUseCase = getWatchlistUseCase,
        getQuoteStreamUseCase = getQuoteStreamUseCase,
        getQuoteUseCase = getQuoteUseCase,
        addToWatchlistUseCase = addToWatchlistUseCase,
        removeFromWatchlistUseCase = removeFromWatchlistUseCase,
        getAvailableSymbolsUseCase = getAvailableSymbolsUseCase,
        getSymbolHistoryUseCase = getSymbolHistoryUseCase,
        getPublicWsConnectionStateUseCase = getPublicWsConnectionStateUseCase,
    ).also { viewModel = it }

    // ── Debounce ─────────────────────────────────────────────────────────────

    @Test
    fun `rapid search query changes trigger a single debounced call with the last query`() = runTest {
        coEvery {
            getAvailableSymbolsUseCase(search = "AAPL", limit = any(), offset = 0)
        } returns Result.success(
            SymbolPage(items = listOf(SymbolInfo("AAPL", "Apple Inc.", "NASDAQ", "USD")), hasMore = false, nextOffset = 1),
        )

        createViewModel()

        viewModel.onSymbolSearchQueryChanged("A")
        viewModel.onSymbolSearchQueryChanged("AA")
        viewModel.onSymbolSearchQueryChanged("AAPL")

        // Still within the debounce window — no call fired yet.
        advanceTimeBy(299L)
        coVerify(exactly = 0) { getAvailableSymbolsUseCase(search = any(), limit = any(), offset = any()) }

        // Past the 300 ms debounce window — exactly one call, with the last query.
        advanceTimeBy(50L)
        coVerify(exactly = 1) { getAvailableSymbolsUseCase(search = "AAPL", limit = any(), offset = 0) }
        coVerify(exactly = 0) { getAvailableSymbolsUseCase(search = "A", limit = any(), offset = any()) }
        coVerify(exactly = 0) { getAvailableSymbolsUseCase(search = "AA", limit = any(), offset = any()) }

        val state = viewModel.symbolPickerState.value
        assertTrue("Expected Success, got $state", state is SymbolPickerUiState.Success)
        assertEquals(listOf("AAPL"), (state as SymbolPickerUiState.Success).symbols.map { it.ticker })

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `blank search query is sent as null`() = runTest {
        coEvery {
            getAvailableSymbolsUseCase(search = null, limit = any(), offset = 0)
        } returns Result.success(SymbolPage(items = emptyList(), hasMore = false, nextOffset = 0))

        createViewModel()
        viewModel.onSymbolSearchQueryChanged("   ")
        advanceTimeBy(301L) // advanceTimeBy exclut les tâches planifiées exactement à t+300

        coVerify(exactly = 1) { getAvailableSymbolsUseCase(search = null, limit = any(), offset = 0) }

        viewModel.viewModelScope.cancel()
    }

    // ── Load more ────────────────────────────────────────────────────────────

    @Test
    fun `loadMoreSymbols appends the next page at the returned offset`() = runTest {
        val firstPage = SymbolPage(
            items = listOf(SymbolInfo("AAPL", "Apple Inc.", "NASDAQ", "USD")),
            hasMore = true,
            nextOffset = 1,
        )
        val secondPage = SymbolPage(
            items = listOf(SymbolInfo("MSFT", "Microsoft Corporation", "NASDAQ", "USD")),
            hasMore = false,
            nextOffset = 2,
        )
        coEvery { getAvailableSymbolsUseCase(search = null, limit = any(), offset = 0) } returns Result.success(firstPage)
        coEvery { getAvailableSymbolsUseCase(search = null, limit = any(), offset = 1) } returns Result.success(secondPage)

        createViewModel()
        viewModel.refreshSymbols()
        viewModel.loadMoreSymbols()

        val state = viewModel.symbolPickerState.value
        assertTrue("Expected Success, got $state", state is SymbolPickerUiState.Success)
        val success = state as SymbolPickerUiState.Success
        assertEquals(listOf("AAPL", "MSFT"), success.symbols.map { it.ticker })
        assertEquals(2, success.nextOffset)
        assertFalse(success.hasMore)
        assertFalse(success.isLoadingMore)

        coVerify(exactly = 1) { getAvailableSymbolsUseCase(search = null, limit = any(), offset = 1) }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `loadMoreSymbols is a no-op when hasMore is false`() = runTest {
        val onlyPage = SymbolPage(
            items = listOf(SymbolInfo("AAPL", "Apple Inc.")),
            hasMore = false,
            nextOffset = 1,
        )
        coEvery { getAvailableSymbolsUseCase(search = null, limit = any(), offset = 0) } returns Result.success(onlyPage)

        createViewModel()
        viewModel.refreshSymbols()
        viewModel.loadMoreSymbols()

        coVerify(exactly = 0) { getAvailableSymbolsUseCase(search = null, limit = any(), offset = 1) }

        viewModel.viewModelScope.cancel()
    }

    // ── Errors ───────────────────────────────────────────────────────────────

    @Test
    fun `refreshSymbols surfaces an Error state when the use case fails`() = runTest {
        coEvery {
            getAvailableSymbolsUseCase(search = null, limit = any(), offset = 0)
        } returns Result.failure(RuntimeException("boom"))

        createViewModel()
        viewModel.refreshSymbols()

        val state = viewModel.symbolPickerState.value
        assertTrue("Expected Error, got $state", state is SymbolPickerUiState.Error)

        viewModel.viewModelScope.cancel()
    }

    // ── WS public + REST fallback (PR 3.4, audit #13/#14) ────────────────────

    @Test
    fun `REST fallback polls watchlist symbols only while public WS is not Connected`() = runTest {
        publicWsState.value = WsConnectionState.Disconnected
        every { getWatchlistUseCase() } returns MutableStateFlow(listOf("AAPL"))
        coEvery { getQuoteUseCase("AAPL") } returns Result.success(fakeQuote)

        createViewModel()
        advanceTimeBy(2_001L) // debounce Disconnected

        coVerify(exactly = 1) { getQuoteUseCase("AAPL") }
        val state = viewModel.uiState.value as MarketDataUiState.Success
        assertEquals(fakeQuote, state.quotes["AAPL"])

        // Reconnexion → le polling s'arrête
        publicWsState.value = WsConnectionState.Connected
        advanceTimeBy(120_000L)
        coVerify(exactly = 1) { getQuoteUseCase("AAPL") }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `public WS drop flags the cached WS quote as stale`() = runTest {
        val wsFlow = MutableSharedFlow<Quote>()
        every { getWatchlistUseCase() } returns MutableStateFlow(listOf("AAPL"))
        every { getQuoteStreamUseCase("AAPL") } returns wsFlow
        coEvery { getQuoteUseCase("AAPL") } returns Result.failure(IOException("timeout"))

        createViewModel()
        wsFlow.emit(fakeQuote.copy(source = "ws_public"))
        assertTrue((viewModel.uiState.value as MarketDataUiState.Success).staleSymbols.isEmpty())

        publicWsState.value = WsConnectionState.Disconnected
        advanceTimeBy(2_001L)

        val state = viewModel.uiState.value as MarketDataUiState.Success
        assertEquals(setOf("AAPL"), state.staleSymbols)
        assertEquals("ws_public", state.quotes["AAPL"]?.source)

        // Cours WS après reconnexion → plus stale (flux jamais annulé)
        publicWsState.value = WsConnectionState.Connected
        wsFlow.emit(fakeQuote.copy(source = "ws_public", price = BigDecimal("180.00")))
        val live = viewModel.uiState.value as MarketDataUiState.Success
        assertTrue(live.staleSymbols.isEmpty())
        assertEquals(BigDecimal("180.00"), live.quotes["AAPL"]?.price)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `removing a symbol from the watchlist stops its REST polling`() = runTest {
        publicWsState.value = WsConnectionState.Disconnected
        val watchlist = MutableStateFlow(listOf("AAPL"))
        every { getWatchlistUseCase() } returns watchlist
        coEvery { getQuoteUseCase("AAPL") } returns Result.success(fakeQuote)

        createViewModel()
        advanceTimeBy(2_001L)
        coVerify(exactly = 1) { getQuoteUseCase("AAPL") }

        // Ancien bug (B-ws-b-2) : catch(Exception) attrapait la CancellationException et
        // relançait un polling pour le symbole retiré.
        watchlist.value = emptyList()
        advanceTimeBy(120_000L)
        coVerify(exactly = 1) { getQuoteUseCase("AAPL") }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `no REST polling while the app is in background`() = runTest {
        publicWsState.value = WsConnectionState.Disconnected
        appForeground.value = false
        every { getWatchlistUseCase() } returns MutableStateFlow(listOf("AAPL"))
        coEvery { getQuoteUseCase("AAPL") } returns Result.success(fakeQuote)

        createViewModel()
        advanceTimeBy(120_000L)
        coVerify(exactly = 0) { getQuoteUseCase(any()) }

        viewModel.viewModelScope.cancel()
    }

    // ── Pull-to-refresh : un vrai indicateur, borné à la durée des fetch REST ──

    @Test
    fun `refresh exposes isRefreshing until every REST fetch has finished`() = runTest {
        every { getWatchlistUseCase() } returns MutableStateFlow(listOf("AAPL"))
        val gate = CompletableDeferred<Unit>()
        coEvery { getQuoteUseCase("AAPL") } coAnswers {
            gate.await()
            Result.success(fakeQuote)
        }
        createViewModel()
        assertFalse(viewModel.isRefreshing.value)

        viewModel.refresh()
        assertTrue("spinner expected while the REST fetch is in flight", viewModel.isRefreshing.value)

        gate.complete(Unit)
        assertFalse(viewModel.isRefreshing.value)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `a refresh failure still clears isRefreshing`() = runTest {
        every { getWatchlistUseCase() } returns MutableStateFlow(listOf("AAPL"))
        coEvery { getQuoteUseCase("AAPL") } returns Result.failure(java.io.IOException("timeout"))
        createViewModel()

        viewModel.refresh()

        assertFalse(viewModel.isRefreshing.value)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `refresh while already refreshing is ignored`() = runTest {
        every { getWatchlistUseCase() } returns MutableStateFlow(listOf("AAPL"))
        val gate = CompletableDeferred<Unit>()
        coEvery { getQuoteUseCase("AAPL") } coAnswers {
            gate.await()
            Result.success(fakeQuote)
        }
        createViewModel()

        viewModel.refresh()
        viewModel.refresh()
        viewModel.refresh()
        gate.complete(Unit)

        coVerify(exactly = 1) { getQuoteUseCase("AAPL") }
        viewModel.viewModelScope.cancel()
    }
}
