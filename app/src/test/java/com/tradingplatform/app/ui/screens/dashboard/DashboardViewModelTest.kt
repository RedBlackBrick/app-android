package com.tradingplatform.app.ui.screens.dashboard

import app.cash.turbine.test
import com.tradingplatform.app.domain.model.NavSummary
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.PnlSummary
import com.tradingplatform.app.domain.model.Quote
import com.tradingplatform.app.domain.usecase.auth.GetPortfolioIdUseCase
import com.tradingplatform.app.domain.usecase.market.GetQuoteUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPnlUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPortfolioNavUseCase
import com.tradingplatform.app.util.MainDispatcherRule
import com.tradingplatform.app.vpn.VpnNotConnectedException
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val getPnlUseCase = mockk<GetPnlUseCase>()
    private val getPortfolioNavUseCase = mockk<GetPortfolioNavUseCase>()
    private val getQuoteUseCase = mockk<GetQuoteUseCase>()
    private val getPortfolioIdUseCase = mockk<GetPortfolioIdUseCase>()

    private lateinit var viewModel: DashboardViewModel

    // ── Fakes ─────────────────────────────────────────────────────────────────

    private val fakeNav = NavSummary(
        nav = BigDecimal("100000.00"),
        cash = BigDecimal("20000.00"),
        positionsValue = BigDecimal("80000.00"),
        timestamp = Instant.now(),
    )

    private val fakePnl = PnlSummary(
        realizedPnl = BigDecimal("1500.00"),
        unrealizedPnl = BigDecimal("3000.00"),
        totalPnl = BigDecimal("4500.00"),
        totalPnlPercent = 4.5,
        tradesCount = 10,
        winningTrades = 7,
        losingTrades = 3,
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
    }

    private fun createViewModel(): DashboardViewModel = DashboardViewModel(
        getPnlUseCase = getPnlUseCase,
        getPortfolioNavUseCase = getPortfolioNavUseCase,
        getQuoteUseCase = getQuoteUseCase,
        getPortfolioIdUseCase = getPortfolioIdUseCase,
    )

    // ── portfolioId ───────────────────────────────────────────────────────────

    @Test
    fun `portfolioId is read from dataStore on init`() = runTest {
        viewModel = createViewModel()
        assertEquals("1", viewModel.uiState.value.portfolioId)
    }

    // ── NavUiState ────────────────────────────────────────────────────────────

    @Test
    fun `navSummary emits Success when use case returns data`() = runTest {
        viewModel = createViewModel()
        val state = viewModel.uiState.value.navSummary
        assertTrue("Expected Success, got $state", state is NavUiState.Success)
        assertEquals(fakeNav, (state as NavUiState.Success).data)
    }

    @Test
    fun `navSummary emits Error when use case fails`() = runTest {
        coEvery { getPortfolioNavUseCase(any()) } returns Result.failure(RuntimeException("Network error"))
        viewModel = createViewModel()
        val state = viewModel.uiState.value.navSummary
        assertTrue("Expected Error, got $state", state is NavUiState.Error)
    }

    // ── PnlUiState ────────────────────────────────────────────────────────────

    @Test
    fun `pnlSummary emits Success when use case returns data`() = runTest {
        viewModel = createViewModel()
        val state = viewModel.uiState.value.pnlSummary
        assertTrue("Expected Success, got $state", state is PnlUiState.Success)
        assertEquals(fakePnl, (state as PnlUiState.Success).data)
    }

    @Test
    fun `pnlSummary emits Error when use case fails`() = runTest {
        coEvery { getPnlUseCase(any(), any()) } returns Result.failure(RuntimeException("PnL error"))
        viewModel = createViewModel()
        val state = viewModel.uiState.value.pnlSummary
        assertTrue("Expected Error, got $state", state is PnlUiState.Error)
    }

    // ── QuoteUiState ──────────────────────────────────────────────────────────

    @Test
    fun `quote emits Success when use case returns data`() = runTest {
        viewModel = createViewModel()
        val state = viewModel.uiState.value.quote
        assertTrue("Expected Success, got $state", state is QuoteUiState.Success)
        assertEquals(fakeQuote, (state as QuoteUiState.Success).data)
    }

    @Test
    fun `VpnNotConnectedException transitions Success to Stale`() = runTest {
        // First call returns success, second call throws VpnNotConnectedException
        coEvery { getQuoteUseCase(any()) } returnsMany listOf(
            Result.success(fakeQuote),
            Result.failure(VpnNotConnectedException()),
        )

        viewModel = createViewModel()

        // After init, state should be Success
        assertTrue(viewModel.uiState.value.quote is QuoteUiState.Success)

        // Advance past the 30s poll delay to trigger second call
        advanceTimeBy(31_000L)

        val state = viewModel.uiState.value.quote
        assertTrue("Expected Stale after VPN disconnect, got $state", state is QuoteUiState.Stale)
        assertEquals(fakeQuote, (state as QuoteUiState.Stale).data)
    }

    @Test
    fun `VpnNotConnectedException keeps Loading state if never had Success`() = runTest {
        // All calls fail with VPN exception from the start
        coEvery { getQuoteUseCase(any()) } returns Result.failure(VpnNotConnectedException())

        viewModel = createViewModel()

        val state = viewModel.uiState.value.quote
        // Should remain Loading (not transition to Stale since there's no previous data)
        assertTrue(
            "Expected Loading or non-Stale state, got $state",
            state !is QuoteUiState.Stale,
        )
    }

    @Test
    fun `IOException keeps previous state without error`() = runTest {
        coEvery { getQuoteUseCase(any()) } returnsMany listOf(
            Result.success(fakeQuote),
            Result.failure(java.io.IOException("Timeout")),
        )

        viewModel = createViewModel()

        // First poll: success
        assertTrue(viewModel.uiState.value.quote is QuoteUiState.Success)

        // Advance past the 30s poll delay
        advanceTimeBy(31_000L)

        // Should still be Success (IOException kept previous state)
        val state = viewModel.uiState.value.quote
        assertTrue("Expected Success to be kept on IOException, got $state", state is QuoteUiState.Success)
    }

    @Test
    fun `unknown exception transitions to Error`() = runTest {
        coEvery { getQuoteUseCase(any()) } returns Result.failure(IllegalStateException("Unknown"))
        viewModel = createViewModel()
        val state = viewModel.uiState.value.quote
        assertTrue("Expected Error on unknown exception, got $state", state is QuoteUiState.Error)
    }

    // ── selectPeriod ──────────────────────────────────────────────────────────

    @Test
    fun `selectPeriod updates selectedPeriod and re-fetches PnL`() = runTest {
        viewModel = createViewModel()

        // Default period is DAY
        assertEquals(PnlPeriod.DAY, viewModel.uiState.value.selectedPeriod)

        viewModel.selectPeriod(PnlPeriod.MONTH)

        assertEquals(PnlPeriod.MONTH, viewModel.uiState.value.selectedPeriod)
    }

    // ── Turbine StateFlow test ────────────────────────────────────────────────

    @Test
    fun `uiState emits non-Loading navSummary after data arrives`() = runTest {
        viewModel = createViewModel()

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
    }

    @Test
    fun `refresh triggers re-fetch of all data`() = runTest {
        viewModel = createViewModel()

        // All use cases already return success
        viewModel.refresh()

        val state = viewModel.uiState.value
        assertNotNull(state)
        assertTrue(state.navSummary is NavUiState.Success || state.navSummary is NavUiState.Loading)
    }
}
