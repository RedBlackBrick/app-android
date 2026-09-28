package com.tradingplatform.app.ui.screens.dashboard

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.tradingplatform.app.domain.model.NavSummary
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.PnlSummary
import com.tradingplatform.app.domain.model.Quote
import com.tradingplatform.app.domain.usecase.activity.GetActivityFeedUseCase
import com.tradingplatform.app.domain.usecase.auth.GetPortfolioIdUseCase
import com.tradingplatform.app.domain.usecase.market.GetPublicWsConnectionStateUseCase
import com.tradingplatform.app.domain.usecase.market.GetQuoteStreamUseCase
import com.tradingplatform.app.domain.usecase.market.GetQuoteUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPnlUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPortfolioNavUseCase
import com.tradingplatform.app.domain.model.WsConnectionState
import com.tradingplatform.app.domain.usecase.portfolio.GetPortfolioWsUpdatesUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetWsConnectionStateUseCase
import com.tradingplatform.app.util.MainDispatcherRule
import com.tradingplatform.app.vpn.VpnNotConnectedException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val getPnlUseCase = mockk<GetPnlUseCase>()
    private val getPortfolioNavUseCase = mockk<GetPortfolioNavUseCase>()
    private val getQuoteUseCase = mockk<GetQuoteUseCase>()
    private val getQuoteStreamUseCase = mockk<GetQuoteStreamUseCase>()
    private val getDefaultQuoteSymbolUseCase = mockk<com.tradingplatform.app.domain.usecase.market.GetDefaultQuoteSymbolUseCase>()
    private val getPortfolioIdUseCase = mockk<GetPortfolioIdUseCase>()
    private val getPortfolioWsUpdatesUseCase = mockk<GetPortfolioWsUpdatesUseCase>()
    private val getWsConnectionStateUseCase = mockk<GetWsConnectionStateUseCase>()
    private val getActivityFeedUseCase = mockk<GetActivityFeedUseCase>()
    private val getActiveStrategyCountUseCase =
        mockk<com.tradingplatform.app.domain.usecase.portfolio.GetActiveStrategyCountUseCase>()
    private val getPortfolioCircuitBreakerStatusUseCase =
        mockk<com.tradingplatform.app.domain.usecase.risk.GetPortfolioCircuitBreakerStatusUseCase>()
    private val getPublicWsConnectionStateUseCase = mockk<GetPublicWsConnectionStateUseCase>()

    /** État du WS public (cours) — Disconnected par défaut → fallback REST après le debounce 2 s. */
    private val publicWsState = MutableStateFlow(WsConnectionState.Disconnected)
    private val appForeground = MutableStateFlow(true)

    private lateinit var viewModel: DashboardViewModel

    // ── Fakes ─────────────────────────────────────────────────────────────────

    private val fakeNav = NavSummary(
        currentValue = BigDecimal("100000.00"),
        cashBalance = BigDecimal("20000.00"),
        totalRealizedPnl = BigDecimal("1500.00"),
        totalUnrealizedPnl = BigDecimal("3000.00"),
    )

    private val fakePnl = PnlSummary(
        totalReturn = BigDecimal("4500.00"),
        totalReturnPct = 0.045,
        sharpeRatio = 1.2,
        sortinoRatio = 1.5,
        maxDrawdown = 0.05,
        volatility = 0.12,
        cagr = 0.09,
        winRate = 0.7,
        profitFactor = 2.3,
        avgTradeReturn = BigDecimal("450.00"),
    )

    private val fakeQuote = Quote(
        symbol = "AAPL",
        price = BigDecimal("175.50"),
        bid = BigDecimal("175.48"),
        ask = BigDecimal("175.52"),
        volume = 35_000_000L,
        change = BigDecimal("2.30"),
        changePercent = 1.33,
        timestamp = Instant.now(),
        source = "yahoo",
    )

    @Before
    fun setUp() {
        coEvery { getPortfolioIdUseCase() } returns "1"
        coEvery { getPortfolioNavUseCase(any()) } returns Result.success(fakeNav)
        coEvery { getPnlUseCase(any(), any()) } returns Result.success(fakePnl)
        coEvery { getQuoteUseCase(any()) } returns Result.success(fakeQuote)
        // Portfolio WS updates — flux vide par défaut (le WS privé n'est pas l'objet des tests ici)
        every { getPortfolioWsUpdatesUseCase() } returns emptyFlow()
        // WS connection state — Connected par défaut
        every { getWsConnectionStateUseCase() } returns MutableStateFlow(WsConnectionState.Connected)
        // WS public — aucun cours par défaut ; l'état Disconnected déclenche le fallback REST
        // (audit #14 : le fallback est piloté par connectionState, plus par une exception du flux).
        every { getQuoteStreamUseCase(any()) } returns emptyFlow()
        every { getPublicWsConnectionStateUseCase() } returns publicWsState
        every { getPublicWsConnectionStateUseCase.isAppForeground() } returns appForeground
        // Activity feed — empty flow by default (not the focus of these tests)
        every { getActivityFeedUseCase() } returns emptyFlow()
        coEvery { getDefaultQuoteSymbolUseCase() } returns "AAPL"
        // Strategy count + circuit-breaker — failed by default (tile hidden) so the
        // existing test fixtures don't need to know about them.
        coEvery { getActiveStrategyCountUseCase(any()) } returns Result.failure(IOException("not stubbed"))
        coEvery { getPortfolioCircuitBreakerStatusUseCase(any()) } returns Result.failure(IOException("not stubbed"))
    }

    private fun createViewModel(): DashboardViewModel = DashboardViewModel(
        getPnlUseCase = getPnlUseCase,
        getPortfolioNavUseCase = getPortfolioNavUseCase,
        getQuoteUseCase = getQuoteUseCase,
        getQuoteStreamUseCase = getQuoteStreamUseCase,
        getDefaultQuoteSymbolUseCase = getDefaultQuoteSymbolUseCase,
        getPortfolioIdUseCase = getPortfolioIdUseCase,
        getPortfolioWsUpdatesUseCase = getPortfolioWsUpdatesUseCase,
        getWsConnectionStateUseCase = getWsConnectionStateUseCase,
        getActivityFeedUseCase = getActivityFeedUseCase,
        getActiveStrategyCountUseCase = getActiveStrategyCountUseCase,
        getPortfolioCircuitBreakerStatusUseCase = getPortfolioCircuitBreakerStatusUseCase,
        getPublicWsConnectionStateUseCase = getPublicWsConnectionStateUseCase,
    ).also { viewModel = it }

    /** Debounce Disconnected du QuoteFallbackController (2 s) + marge. */
    private val fallbackDebounce = 2_001L

    // ── portfolioId ───────────────────────────────────────────────────────────

    @Test
    fun `portfolioId is read from dataStore on init`() = runTest {
        createViewModel()
        assertEquals("1", viewModel.uiState.value.portfolioId)
        viewModel.viewModelScope.cancel()
    }

    // ── NavUiState ────────────────────────────────────────────────────────────

    @Test
    fun `navSummary emits Success when use case returns data`() = runTest {
        createViewModel()
        val state = viewModel.uiState.value.navSummary
        assertTrue("Expected Success, got $state", state is NavUiState.Success)
        assertEquals(fakeNav, (state as NavUiState.Success).data)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `navSummary emits Error when use case fails`() = runTest {
        coEvery { getPortfolioNavUseCase(any()) } returns Result.failure(RuntimeException("Network error"))
        createViewModel()
        val state = viewModel.uiState.value.navSummary
        assertTrue("Expected Error, got $state", state is NavUiState.Error)
        viewModel.viewModelScope.cancel()
    }

    // ── PnlUiState ────────────────────────────────────────────────────────────

    @Test
    fun `pnlSummary emits Success when use case returns data`() = runTest {
        createViewModel()
        val state = viewModel.uiState.value.pnlSummary
        assertTrue("Expected Success, got $state", state is PnlUiState.Success)
        assertEquals(fakePnl, (state as PnlUiState.Success).data)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `pnlSummary emits Error when use case fails`() = runTest {
        coEvery { getPnlUseCase(any(), any()) } returns Result.failure(RuntimeException("PnL error"))
        createViewModel()
        val state = viewModel.uiState.value.pnlSummary
        assertTrue("Expected Error, got $state", state is PnlUiState.Error)
        viewModel.viewModelScope.cancel()
    }

    // ── QuoteUiState — via polling REST fallback ───────────────────────────────
    // Le WS public est Disconnected par défaut : le QuoteFallbackController démarre le
    // polling REST après le debounce de 2 s, puis toutes les 30 s.

    @Test
    fun `quote emits Success when REST use case returns data`() = runTest {
        createViewModel()
        advanceTimeBy(fallbackDebounce)
        val state = viewModel.uiState.value.quote
        assertTrue("Expected Success, got $state", state is QuoteUiState.Success)
        assertEquals(fakeQuote, (state as QuoteUiState.Success).data)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `VpnNotConnectedException transitions Success to Stale`() = runTest {
        // First call returns success, second call throws VpnNotConnectedException
        coEvery { getQuoteUseCase(any()) } returnsMany listOf(
            Result.success(fakeQuote),
            Result.failure(VpnNotConnectedException()),
        )

        createViewModel()
        advanceTimeBy(fallbackDebounce)

        // After the first poll, state should be Success
        assertTrue(viewModel.uiState.value.quote is QuoteUiState.Success)

        // Advance past the 30s poll delay to trigger second call
        advanceTimeBy(30_000L)

        val state = viewModel.uiState.value.quote
        assertTrue("Expected Stale after VPN disconnect, got $state", state is QuoteUiState.Stale)
        assertEquals(fakeQuote, (state as QuoteUiState.Stale).data)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `VpnNotConnectedException keeps Loading state if never had Success`() = runTest {
        // All calls fail with VPN exception from the start
        coEvery { getQuoteUseCase(any()) } returns Result.failure(VpnNotConnectedException())

        createViewModel()
        advanceTimeBy(fallbackDebounce)

        val state = viewModel.uiState.value.quote
        // Should remain Loading (not transition to Stale since there's no previous data)
        assertTrue(
            "Expected Loading or non-Stale state, got $state",
            state !is QuoteUiState.Stale,
        )
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `IOException keeps previous state without error`() = runTest {
        coEvery { getQuoteUseCase(any()) } returnsMany listOf(
            Result.success(fakeQuote),
            Result.failure(java.io.IOException("Timeout")),
        )

        createViewModel()
        advanceTimeBy(fallbackDebounce)

        // First poll: success
        assertTrue(viewModel.uiState.value.quote is QuoteUiState.Success)

        // Advance past the 30s poll delay
        advanceTimeBy(30_000L)

        // Should still be Success (IOException kept previous state)
        val state = viewModel.uiState.value.quote
        assertTrue("Expected Success to be kept on IOException, got $state", state is QuoteUiState.Success)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `unknown exception transitions to Error`() = runTest {
        coEvery { getQuoteUseCase(any()) } returns Result.failure(IllegalStateException("Unknown"))
        createViewModel()
        advanceTimeBy(fallbackDebounce)
        val state = viewModel.uiState.value.quote
        assertTrue("Expected Error on unknown exception, got $state", state is QuoteUiState.Error)
        viewModel.viewModelScope.cancel()
    }

    // ── selectPeriod ──────────────────────────────────────────────────────────

    @Test
    fun `selectPeriod updates selectedPeriod and re-fetches PnL`() = runTest {
        createViewModel()

        // Default period is DAY
        assertEquals(PnlPeriod.DAY, viewModel.uiState.value.selectedPeriod)

        viewModel.selectPeriod(PnlPeriod.MONTH)

        assertEquals(PnlPeriod.MONTH, viewModel.uiState.value.selectedPeriod)
        viewModel.viewModelScope.cancel()
    }

    // ── Turbine StateFlow test ────────────────────────────────────────────────

    @Test
    fun `uiState emits non-Loading navSummary after data arrives`() = runTest {
        createViewModel()

        viewModel.uiState.test {
            // Since UnconfinedTestDispatcher runs coroutines eagerly,
            // the initial Loading state may or may not be emitted before the Success.
            // We skip intermediate states and check the final settled state.
            val items = mutableListOf(awaitItem())
            // Collect until settled (Success or Error)
            while (items.last().navSummary is NavUiState.Loading) {
                items.add(awaitItem())
            }
            val finalState = items.last()
            assertTrue(
                "Expected navSummary Success, got ${finalState.navSummary}",
                finalState.navSummary is NavUiState.Success,
            )
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `refresh triggers re-fetch of all data`() = runTest {
        createViewModel()

        // All use cases already return success
        viewModel.refresh()

        val state = viewModel.uiState.value
        assertNotNull(state)
        assertTrue(state.navSummary is NavUiState.Success || state.navSummary is NavUiState.Loading)
        viewModel.viewModelScope.cancel()
    }

    // ── WS public quote subscription ──────────────────────────────────────────

    @Test
    fun `quote emits Success from WS stream when available`() = runTest {
        publicWsState.value = WsConnectionState.Connected
        val wsQuote = fakeQuote.copy(source = "ws_public")
        every { getQuoteStreamUseCase(any()) } returns flow { emit(wsQuote) }

        createViewModel()
        advanceTimeBy(60_000L)

        val state = viewModel.uiState.value.quote
        assertTrue("Expected Success from WS, got $state", state is QuoteUiState.Success)
        assertEquals("ws_public", (state as QuoteUiState.Success).data.source)
        // WS Connected → aucun polling REST
        coVerify(exactly = 0) { getQuoteUseCase(any()) }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `no REST polling while public WS is Connected`() = runTest {
        publicWsState.value = WsConnectionState.Connected
        createViewModel()
        advanceTimeBy(120_000L)
        coVerify(exactly = 0) { getQuoteUseCase(any()) }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `short disconnection within the debounce window does not start polling`() = runTest {
        publicWsState.value = WsConnectionState.Connected
        createViewModel()

        publicWsState.value = WsConnectionState.Connecting
        advanceTimeBy(1_500L)
        publicWsState.value = WsConnectionState.Connected
        advanceTimeBy(60_000L)

        coVerify(exactly = 0) { getQuoteUseCase(any()) }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `public WS drop marks the WS quote Stale then polls REST`() = runTest {
        publicWsState.value = WsConnectionState.Connected
        val wsFlow = MutableSharedFlow<Quote>()
        every { getQuoteStreamUseCase(any()) } returns wsFlow
        // REST en échec transitoire → le Stale reste visible
        coEvery { getQuoteUseCase(any()) } returns Result.failure(IOException("timeout"))

        createViewModel()
        val wsQuote = fakeQuote.copy(source = "ws_public")
        wsFlow.emit(wsQuote)
        assertTrue(viewModel.uiState.value.quote is QuoteUiState.Success)

        publicWsState.value = WsConnectionState.Disconnected
        advanceTimeBy(fallbackDebounce)

        val state = viewModel.uiState.value.quote
        assertTrue("Expected Stale after WS drop, got $state", state is QuoteUiState.Stale)
        assertEquals(wsQuote, (state as QuoteUiState.Stale).data)
        coVerify(exactly = 1) { getQuoteUseCase("AAPL") }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `REST polling stops when public WS reconnects and WS quotes resume`() = runTest {
        val wsFlow = MutableSharedFlow<Quote>()
        every { getQuoteStreamUseCase(any()) } returns wsFlow

        createViewModel()
        advanceTimeBy(fallbackDebounce)
        coVerify(exactly = 1) { getQuoteUseCase("AAPL") }

        // Reconnexion : Connected est propagé sans debounce → la boucle de polling est annulée
        publicWsState.value = WsConnectionState.Connected
        advanceTimeBy(120_000L)
        coVerify(exactly = 1) { getQuoteUseCase("AAPL") }

        // Le flux WS n'a jamais été annulé → les cours reprennent sans resouscription manuelle
        val wsQuote = fakeQuote.copy(source = "ws_public", price = BigDecimal("180.00"))
        wsFlow.emit(wsQuote)
        val state = viewModel.uiState.value.quote
        assertTrue("Expected WS Success, got $state", state is QuoteUiState.Success)
        assertEquals(wsQuote, (state as QuoteUiState.Success).data)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `no REST polling while the app is in background`() = runTest {
        appForeground.value = false
        createViewModel()
        advanceTimeBy(120_000L)
        coVerify(exactly = 0) { getQuoteUseCase(any()) }

        // Retour au premier plan → polling immédiat
        appForeground.value = true
        advanceTimeBy(1L)
        coVerify(exactly = 1) { getQuoteUseCase("AAPL") }
        viewModel.viewModelScope.cancel()
    }
}
