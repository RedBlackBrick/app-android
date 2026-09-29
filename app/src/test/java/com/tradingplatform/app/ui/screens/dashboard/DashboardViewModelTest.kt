package com.tradingplatform.app.ui.screens.dashboard

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.tradingplatform.app.domain.model.NavSummary
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.PnlSummary
import com.tradingplatform.app.domain.model.WsUpdate
import com.tradingplatform.app.domain.usecase.activity.GetActivityFeedUseCase
import com.tradingplatform.app.domain.usecase.auth.GetPortfolioIdUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPnlUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPortfolioNavUseCase
import com.tradingplatform.app.domain.model.WsConnectionState
import com.tradingplatform.app.domain.usecase.portfolio.GetPortfolioWsUpdatesUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetWsConnectionStateUseCase
import com.tradingplatform.app.ui.common.DataState
import com.tradingplatform.app.util.MainDispatcherRule
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val getPnlUseCase = mockk<GetPnlUseCase>()
    private val getPortfolioNavUseCase = mockk<GetPortfolioNavUseCase>()
    private val getPortfolioIdUseCase = mockk<GetPortfolioIdUseCase>()
    private val getPortfolioWsUpdatesUseCase = mockk<GetPortfolioWsUpdatesUseCase>()
    private val getWsConnectionStateUseCase = mockk<GetWsConnectionStateUseCase>()
    private val getActivityFeedUseCase = mockk<GetActivityFeedUseCase>()
    private val getActiveStrategyCountUseCase =
        mockk<com.tradingplatform.app.domain.usecase.portfolio.GetActiveStrategyCountUseCase>()
    private val getPortfolioCircuitBreakerStatusUseCase =
        mockk<com.tradingplatform.app.domain.usecase.risk.GetPortfolioCircuitBreakerStatusUseCase>()

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

    @Before
    fun setUp() {
        coEvery { getPortfolioIdUseCase() } returns "1"
        coEvery { getPortfolioNavUseCase(any()) } returns Result.success(fakeNav)
        coEvery { getPnlUseCase(any(), any()) } returns Result.success(fakePnl)
        // Portfolio WS updates — flux vide par défaut (le WS privé n'est pas l'objet des tests ici)
        every { getPortfolioWsUpdatesUseCase() } returns emptyFlow()
        // WS connection state — Connected par défaut
        every { getWsConnectionStateUseCase() } returns MutableStateFlow(WsConnectionState.Connected)
        // Activity feed — empty flow by default (not the focus of these tests)
        every { getActivityFeedUseCase() } returns emptyFlow()
        // Strategy count + circuit-breaker — failed by default (tile hidden) so the
        // existing test fixtures don't need to know about them.
        coEvery { getActiveStrategyCountUseCase(any()) } returns Result.failure(IOException("not stubbed"))
        coEvery { getPortfolioCircuitBreakerStatusUseCase(any()) } returns Result.failure(IOException("not stubbed"))
    }

    private fun createViewModel(): DashboardViewModel = DashboardViewModel(
        getPnlUseCase = getPnlUseCase,
        getPortfolioNavUseCase = getPortfolioNavUseCase,
        getPortfolioIdUseCase = getPortfolioIdUseCase,
        getPortfolioWsUpdatesUseCase = getPortfolioWsUpdatesUseCase,
        getWsConnectionStateUseCase = getWsConnectionStateUseCase,
        getActivityFeedUseCase = getActivityFeedUseCase,
        getActiveStrategyCountUseCase = getActiveStrategyCountUseCase,
        getPortfolioCircuitBreakerStatusUseCase = getPortfolioCircuitBreakerStatusUseCase,
    ).also { viewModel = it }

    // ── portfolioId ───────────────────────────────────────────────────────────

    @Test
    fun `portfolioId is read from dataStore on init`() = runTest {
        createViewModel()
        assertEquals("1", viewModel.uiState.value.portfolioId)
        viewModel.viewModelScope.cancel()
    }

    // ── NAV (DataState) ───────────────────────────────────────────────────────

    @Test
    fun `initial DashboardUiState is initial loading for NAV and PnL`() {
        val state = DashboardUiState()
        assertTrue(state.navSummary.isInitialLoading)
        assertTrue(state.pnlSummary.isInitialLoading)
    }

    @Test
    fun `navSummary holds the value when use case returns data`() = runTest {
        createViewModel()
        val state = viewModel.uiState.value.navSummary
        assertEquals(fakeNav, state.value)
        assertNull(state.error)
        assertFalse(state.isRefreshing)
        assertTrue("syncedAt must be set on success", state.syncedAt > 0L)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `initial NAV failure leaves value null with error`() = runTest {
        coEvery { getPortfolioNavUseCase(any()) } returns Result.failure(RuntimeException("Network error"))
        createViewModel()
        val state = viewModel.uiState.value.navSummary
        assertNull(state.value)
        assertEquals("Network error", state.error)
        assertFalse(state.isRefreshing)
        assertFalse(state.isInitialLoading)
        viewModel.viewModelScope.cancel()
    }

    // ── PnL (DataState) ───────────────────────────────────────────────────────

    @Test
    fun `pnlSummary holds the value when use case returns data`() = runTest {
        createViewModel()
        val state = viewModel.uiState.value.pnlSummary
        assertEquals(fakePnl, state.value)
        assertNull(state.error)
        assertFalse(state.isRefreshing)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `initial PnL failure leaves value null with error`() = runTest {
        coEvery { getPnlUseCase(any(), any()) } returns Result.failure(RuntimeException("PnL error"))
        createViewModel()
        val state = viewModel.uiState.value.pnlSummary
        assertNull(state.value)
        assertEquals("PnL error", state.error)
        assertFalse(state.isInitialLoading)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `refresh failure after Success keeps the value and sets the error`() = runTest {
        coEvery { getPortfolioNavUseCase(any()) } returnsMany listOf(
            Result.success(fakeNav),
            Result.failure(RuntimeException("NAV down")),
        )
        coEvery { getPnlUseCase(any(), any()) } returnsMany listOf(
            Result.success(fakePnl),
            Result.failure(RuntimeException("PnL down")),
        )
        createViewModel()
        val syncedAtBefore = viewModel.uiState.value.navSummary.syncedAt

        viewModel.refresh()

        val nav = viewModel.uiState.value.navSummary
        assertEquals("NAV value must survive a failed refresh", fakeNav, nav.value)
        assertEquals("NAV down", nav.error)
        assertFalse(nav.isRefreshing)
        assertFalse(nav.isInitialLoading)
        assertEquals("syncedAt reflects the last successful sync", syncedAtBefore, nav.syncedAt)
        val pnl = viewModel.uiState.value.pnlSummary
        assertEquals("PnL value must survive a failed refresh", fakePnl, pnl.value)
        assertEquals("PnL down", pnl.error)
        viewModel.viewModelScope.cancel()
    }

    // ── WS private — optimistic NAV patch + debounced refetch ─────────────────

    @Test
    fun `WS portfolio update after Success patches NAV directly and keeps values while refreshing`() = runTest {
        val wsFlow = MutableSharedFlow<WsUpdate.PortfolioUpdate>()
        every { getPortfolioWsUpdatesUseCase() } returns wsFlow
        createViewModel()
        assertEquals(fakeNav, viewModel.uiState.value.navSummary.value)

        wsFlow.emit(WsUpdate.PortfolioUpdate(totalValue = 105_000.0, cashBalance = 15_000.0, positionsValue = 90_000.0))

        // Avant le debounce : NAV patchée depuis le WS, PnL conservée, les deux en refresh.
        val nav = viewModel.uiState.value.navSummary
        assertNotNull("NAV value must never be nulled by a WS update", nav.value)
        assertEquals(0, BigDecimal("105000").compareTo(nav.value!!.currentValue))
        assertEquals(0, BigDecimal("15000").compareTo(nav.value!!.cashBalance))
        // Champs absents du WS inchangés
        assertEquals(fakeNav.totalRealizedPnl, nav.value!!.totalRealizedPnl)
        assertTrue(nav.isRefreshing)
        assertFalse(nav.isInitialLoading)
        val pnl = viewModel.uiState.value.pnlSummary
        assertEquals(fakePnl, pnl.value)
        assertTrue(pnl.isRefreshing)
        // Aucun refetch avant l'expiration du debounce
        coVerify(exactly = 1) { getPortfolioNavUseCase(any()) }

        // Après le debounce : refetch REST NAV + PnL, valeur REST appliquée
        advanceTimeBy(WS_REFETCH_DEBOUNCE_MS + 1)
        coVerify(exactly = 2) { getPortfolioNavUseCase(any()) }
        coVerify(exactly = 2) { getPnlUseCase(any(), any()) }
        val after = viewModel.uiState.value
        assertEquals(fakeNav, after.navSummary.value)
        assertFalse(after.navSummary.isRefreshing)
        assertFalse(after.pnlSummary.isRefreshing)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `WS triggered refetch failure keeps the patched NAV and the PnL value`() = runTest {
        val wsFlow = MutableSharedFlow<WsUpdate.PortfolioUpdate>()
        every { getPortfolioWsUpdatesUseCase() } returns wsFlow
        coEvery { getPortfolioNavUseCase(any()) } returnsMany listOf(
            Result.success(fakeNav),
            Result.failure(IOException("timeout")),
        )
        coEvery { getPnlUseCase(any(), any()) } returnsMany listOf(
            Result.success(fakePnl),
            Result.failure(IOException("timeout")),
        )
        createViewModel()

        wsFlow.emit(WsUpdate.PortfolioUpdate(totalValue = 101_000.0))
        advanceTimeBy(WS_REFETCH_DEBOUNCE_MS + 1)

        val nav = viewModel.uiState.value.navSummary
        assertNotNull(nav.value)
        assertEquals(0, BigDecimal("101000").compareTo(nav.value!!.currentValue))
        assertEquals("timeout", nav.error)
        assertFalse(nav.isRefreshing)
        val pnl = viewModel.uiState.value.pnlSummary
        assertEquals(fakePnl, pnl.value)
        assertEquals("timeout", pnl.error)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `burst of 5 WS updates within the debounce triggers a single refetch`() = runTest {
        val wsFlow = MutableSharedFlow<WsUpdate.PortfolioUpdate>()
        every { getPortfolioWsUpdatesUseCase() } returns wsFlow
        createViewModel()
        coVerify(exactly = 1) { getPortfolioNavUseCase(any()) }
        coVerify(exactly = 1) { getPnlUseCase(any(), any()) }

        repeat(5) { i ->
            wsFlow.emit(WsUpdate.PortfolioUpdate(totalValue = 100_000.0 + i))
            advanceTimeBy(100L)
        }
        // Chaque événement patche la NAV immédiatement (dernier total appliqué)
        assertEquals(
            0,
            BigDecimal("100004").compareTo(viewModel.uiState.value.navSummary.value!!.currentValue),
        )

        advanceTimeBy(WS_REFETCH_DEBOUNCE_MS + 1)
        coVerify(exactly = 2) { getPortfolioNavUseCase(any()) }
        coVerify(exactly = 2) { getPnlUseCase(any(), any()) }

        // Plus aucun refetch ensuite
        advanceTimeBy(10_000L)
        coVerify(exactly = 2) { getPortfolioNavUseCase(any()) }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `WS update before the first NAV success does not invent a NAV`() = runTest {
        val wsFlow = MutableSharedFlow<WsUpdate.PortfolioUpdate>()
        every { getPortfolioWsUpdatesUseCase() } returns wsFlow
        coEvery { getPortfolioNavUseCase(any()) } returns Result.failure(RuntimeException("down"))
        createViewModel()

        wsFlow.emit(WsUpdate.PortfolioUpdate(totalValue = 100_000.0))

        val nav = viewModel.uiState.value.navSummary
        assertNull(nav.value)
        assertTrue(nav.isRefreshing)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `patchedWith derives the total from cash plus positions when total_value is absent`() {
        val patched = fakeNav.patchedWith(
            WsUpdate.PortfolioUpdate(cashBalance = 10_000.0, positionsValue = 95_000.0),
        )
        assertEquals(0, BigDecimal("105000").compareTo(patched.currentValue))
        assertEquals(0, BigDecimal("10000").compareTo(patched.cashBalance))

        val unchanged = fakeNav.patchedWith(WsUpdate.PortfolioUpdate(symbol = "AAPL"))
        assertEquals(fakeNav, unchanged)
    }

    @Test
    fun `DataState failure keeps value and success clears error`() {
        val ok = DataState<Int>(isRefreshing = true).success(1, now = 42L)
        val stale = ok.loading().failure("boom")
        assertEquals(1, stale.value)
        assertEquals("boom", stale.error)
        assertEquals(42L, stale.syncedAt)
        assertFalse(stale.isInitialLoading)
        val recovered = stale.loading().success(2, now = 43L)
        assertEquals(DataState(value = 2, isRefreshing = false, error = null, syncedAt = 43L), recovered)
    }

    // ── selectPeriod ──────────────────────────────────────────────────────────

    @Test
    fun `selectPeriod updates selectedPeriod and re-fetches PnL`() = runTest {
        createViewModel()

        // Default period is DAY
        assertEquals(PnlPeriod.DAY, viewModel.uiState.value.selectedPeriod)

        viewModel.selectPeriod(PnlPeriod.MONTH)

        assertEquals(PnlPeriod.MONTH, viewModel.uiState.value.selectedPeriod)
        coVerify(exactly = 1) { getPnlUseCase(any(), PnlPeriod.MONTH) }
        assertEquals(fakePnl, viewModel.uiState.value.pnlSummary.value)
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
            // Collect until settled (value or error)
            while (items.last().navSummary.let { it.value == null && it.error == null }) {
                items.add(awaitItem())
            }
            val finalState = items.last()
            assertEquals(
                "Expected navSummary value, got ${finalState.navSummary}",
                fakeNav,
                finalState.navSummary.value,
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
        // La valeur n'est jamais remise à null pendant / après un refresh réussi
        assertEquals(fakeNav, state.navSummary.value)
        assertEquals(fakePnl, state.pnlSummary.value)
        coVerify(exactly = 2) { getPortfolioNavUseCase(any()) }
        viewModel.viewModelScope.cancel()
    }
}
