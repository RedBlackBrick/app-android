package com.tradingplatform.app.ui.screens.dashboard

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.tradingplatform.app.domain.model.ActivityItem
import com.tradingplatform.app.domain.model.CircuitBreakerState
import com.tradingplatform.app.domain.model.NavCurve
import com.tradingplatform.app.domain.model.NavPoint
import com.tradingplatform.app.domain.model.NavSummary
import com.tradingplatform.app.domain.model.PerformanceMetrics
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.PnlSummary
import com.tradingplatform.app.domain.model.Portfolio
import com.tradingplatform.app.domain.model.PortfolioBrokerStatus
import com.tradingplatform.app.domain.model.PortfolioCircuitBreakerStatus
import com.tradingplatform.app.domain.model.PortfolioOverviewItem
import com.tradingplatform.app.domain.model.RiskStatus
import com.tradingplatform.app.domain.model.WsConnectionState
import com.tradingplatform.app.domain.model.WsUpdate
import com.tradingplatform.app.domain.usecase.activity.GetActivityFeedUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetActiveStrategyCountUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetNavCurveUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPerformanceUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPnlUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPortfolioBrokerStatusUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPortfolioNavUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPortfolioWsUpdatesUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPortfoliosOverviewUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetWsConnectionStateUseCase
import com.tradingplatform.app.domain.usecase.portfolio.ObserveActivePortfolioUseCase
import com.tradingplatform.app.domain.usecase.portfolio.ObservePortfoliosUseCase
import com.tradingplatform.app.domain.usecase.portfolio.RefreshPortfoliosUseCase
import com.tradingplatform.app.domain.usecase.portfolio.SelectPortfolioUseCase
import com.tradingplatform.app.domain.usecase.risk.GetPortfolioCircuitBreakerStatusUseCase
import com.tradingplatform.app.domain.usecase.risk.GetRiskStatusUseCase
import com.tradingplatform.app.ui.common.DataState
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
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val getPnlUseCase = mockk<GetPnlUseCase>()
    private val getPortfolioNavUseCase = mockk<GetPortfolioNavUseCase>()
    private val observeActivePortfolioUseCase = mockk<ObserveActivePortfolioUseCase>()
    private val observePortfoliosUseCase = mockk<ObservePortfoliosUseCase>()
    private val refreshPortfoliosUseCase = mockk<RefreshPortfoliosUseCase>()
    private val selectPortfolioUseCase = mockk<SelectPortfolioUseCase>()
    private val getPortfolioWsUpdatesUseCase = mockk<GetPortfolioWsUpdatesUseCase>()
    private val getWsConnectionStateUseCase = mockk<GetWsConnectionStateUseCase>()
    private val getActivityFeedUseCase = mockk<GetActivityFeedUseCase>()
    private val getActiveStrategyCountUseCase = mockk<GetActiveStrategyCountUseCase>()
    private val getPortfolioCircuitBreakerStatusUseCase = mockk<GetPortfolioCircuitBreakerStatusUseCase>()
    private val getNavCurveUseCase = mockk<GetNavCurveUseCase>()
    private val getPerformanceUseCase = mockk<GetPerformanceUseCase>()
    private val getPortfoliosOverviewUseCase = mockk<GetPortfoliosOverviewUseCase>()
    private val getRiskStatusUseCase = mockk<GetRiskStatusUseCase>()
    private val getPortfolioBrokerStatusUseCase = mockk<GetPortfolioBrokerStatusUseCase>()

    /** Portefeuille actif simulé : modifier `.value` équivaut à un changement de sélection. */
    private val activePortfolio = MutableStateFlow("1")

    /** Liste des portefeuilles du compte (vide tant que « Refresh » n'a rien apporté). */
    private val portfolios = MutableStateFlow<List<Portfolio>>(emptyList())

    /** Flux d'activité temps réel simulé. */
    private val activityFlow = MutableSharedFlow<ActivityItem>()

    private lateinit var viewModel: DashboardViewModel

    // ── Fakes ─────────────────────────────────────────────────────────────────

    private val fakeNav = NavSummary(
        currentValue = BigDecimal("100000.00"),
        cashBalance = BigDecimal("20000.00"),
        totalRealizedPnl = BigDecimal("1500.00"),
        totalUnrealizedPnl = BigDecimal("3000.00"),
    )

    private val fakeNav2 = NavSummary(
        currentValue = BigDecimal("555.00"),
        cashBalance = BigDecimal("55.00"),
        totalRealizedPnl = BigDecimal.ZERO,
        totalUnrealizedPnl = BigDecimal("5.00"),
    )

    private val fakePnl = PnlSummary(
        totalReturn = BigDecimal("4500.00"),
        totalReturnPct = 0.045,
        sharpeRatio = 1.2,
        sortinoRatio = 1.5,
        maxDrawdown = 0.05,
        volatility = 0.12,
        cagr = 0.09,
        winRate = null,
        profitFactor = 2.3,
        avgTradeReturn = BigDecimal("450.00"),
    )

    private val fakePnl2 = fakePnl.copy(totalReturn = BigDecimal("12.00"), totalReturnPct = 0.02)

    private val fakePerformance = PerformanceMetrics(
        totalReturn = BigDecimal("4500.00"),
        totalReturnPct = 0.045,
        sharpeRatio = 1.2,
        sortinoRatio = 1.5,
        maxDrawdown = 0.083,
        volatility = 0.12,
        cagr = 0.09,
        winRate = 0.62,
        profitFactor = 2.3,
        avgTradeReturn = BigDecimal("450.00"),
    )

    private val fakeCurve = NavCurve(
        listOf(
            NavPoint(Instant.parse("2026-09-29T08:00:00Z"), BigDecimal("100000.00")),
            NavPoint(Instant.parse("2026-09-29T12:00:00Z"), BigDecimal("104500.00")),
        ),
    )

    private val killSwitchRisk = RiskStatus(
        killSwitchActive = true,
        killSwitchReason = "Manuel",
        unresolvedViolations = 0,
        dailyLossUsagePct = 0.1,
        drawdownCurrentPct = -0.02,
    )

    private val quietRisk = RiskStatus(
        killSwitchActive = false,
        killSwitchReason = null,
        unresolvedViolations = 0,
        dailyLossUsagePct = 0.1,
        drawdownCurrentPct = -0.02,
    )

    private fun breaker(state: CircuitBreakerState) = PortfolioCircuitBreakerStatus(
        portfolioId = "1",
        enabled = true,
        state = state,
        count = 5,
        threshold = 5,
        windowSeconds = 60,
        ttlSeconds = null,
        redisUnavailable = false,
    )

    private val twoPortfolios = listOf(
        Portfolio(id = "1", name = "Principal", currency = "EUR"),
        Portfolio(id = "2", name = "Actions US", currency = "USD"),
    )

    private val overviewDay = listOf(
        PortfolioOverviewItem("1", "Principal", "EUR", BigDecimal("100000.00"), BigDecimal("450.00"), 0.0045),
        PortfolioOverviewItem("2", "Actions US", "USD", BigDecimal("555.00"), BigDecimal("-5.00"), -0.009),
    )

    private val overviewWeek = listOf(
        PortfolioOverviewItem("1", "Principal", "EUR", BigDecimal("100000.00"), BigDecimal("2100.00"), 0.021),
        PortfolioOverviewItem("2", "Actions US", "USD", BigDecimal("555.00"), BigDecimal("15.00"), 0.028),
    )

    @Before
    fun setUp() {
        every { observeActivePortfolioUseCase() } returns activePortfolio
        every { observePortfoliosUseCase() } returns portfolios
        coEvery { refreshPortfoliosUseCase() } returns Result.success(emptyList())
        coEvery { selectPortfolioUseCase(any()) } returns Result.success(Unit)
        coEvery { getPortfolioNavUseCase(any()) } returns Result.success(fakeNav)
        coEvery { getPnlUseCase(any(), any()) } returns Result.success(fakePnl)
        coEvery { getNavCurveUseCase(any(), any()) } returns Result.success(fakeCurve)
        coEvery { getPerformanceUseCase(any()) } returns Result.success(fakePerformance)
        // Portfolio WS updates — flux vide par défaut (le WS privé n'est pas l'objet des tests ici)
        every { getPortfolioWsUpdatesUseCase() } returns emptyFlow()
        // WS connection state — Connected par défaut
        every { getWsConnectionStateUseCase() } returns MutableStateFlow(WsConnectionState.Connected)
        every { getActivityFeedUseCase() } returns activityFlow
        // Sections optionnelles — en échec par défaut (bloc absent) pour que les fixtures existantes
        // n'aient pas à les connaître.
        coEvery { getActiveStrategyCountUseCase(any()) } returns Result.failure(IOException("not stubbed"))
        coEvery { getPortfolioCircuitBreakerStatusUseCase(any()) } returns Result.failure(IOException("not stubbed"))
        coEvery { getRiskStatusUseCase(any()) } returns Result.failure(IOException("not stubbed"))
        coEvery { getPortfolioBrokerStatusUseCase(any()) } returns Result.failure(IOException("not stubbed"))
        coEvery { getPortfoliosOverviewUseCase(any()) } returns Result.success(overviewDay)
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
    }

    private fun createViewModel(): DashboardViewModel = DashboardViewModel(
        getPnlUseCase = getPnlUseCase,
        getPortfolioNavUseCase = getPortfolioNavUseCase,
        observeActivePortfolioUseCase = observeActivePortfolioUseCase,
        observePortfoliosUseCase = observePortfoliosUseCase,
        refreshPortfoliosUseCase = refreshPortfoliosUseCase,
        selectPortfolioUseCase = selectPortfolioUseCase,
        getPortfolioWsUpdatesUseCase = getPortfolioWsUpdatesUseCase,
        getWsConnectionStateUseCase = getWsConnectionStateUseCase,
        getActivityFeedUseCase = getActivityFeedUseCase,
        getActiveStrategyCountUseCase = getActiveStrategyCountUseCase,
        getPortfolioCircuitBreakerStatusUseCase = getPortfolioCircuitBreakerStatusUseCase,
        getNavCurveUseCase = getNavCurveUseCase,
        getPerformanceUseCase = getPerformanceUseCase,
        getPortfoliosOverviewUseCase = getPortfoliosOverviewUseCase,
        getRiskStatusUseCase = getRiskStatusUseCase,
        getPortfolioBrokerStatusUseCase = getPortfolioBrokerStatusUseCase,
    ).also { viewModel = it }

    private fun catalyst() = ActivityItem.CatalystEvent(
        symbol = "AAPL",
        eventType = "earnings",
        title = "Résultats",
        timestamp = Instant.parse("2026-09-29T09:00:00Z"),
    )

    // ── portefeuille actif ────────────────────────────────────────────────────

    @Test
    fun `portfolioId is the active portfolio on init`() = runTest {
        createViewModel()
        assertEquals("1", viewModel.uiState.value.portfolioId)
    }

    @Test
    fun `startup refreshes the portfolio list once and ignores a failure`() = runTest {
        coEvery { refreshPortfoliosUseCase() } returns Result.failure(IOException("VPN down"))

        createViewModel()

        coVerify(exactly = 1) { refreshPortfoliosUseCase() }
        // L'écran fonctionne avec le portefeuille actif même sans la liste.
        assertEquals(fakeNav, viewModel.uiState.value.navSummary.value)
    }

    @Test
    fun `portfolios are exposed from the observed list`() = runTest {
        portfolios.value = twoPortfolios
        createViewModel()

        assertEquals(twoPortfolios, viewModel.portfolios.value)
    }

    @Test
    fun `everything is loaded with the active portfolio id`() = runTest {
        createViewModel()

        coVerify(exactly = 1) { getPortfolioNavUseCase("1") }
        coVerify(exactly = 1) { getPnlUseCase("1", PnlPeriod.DAY) }
        coVerify(exactly = 1) { getNavCurveUseCase("1", PnlPeriod.DAY) }
        coVerify(exactly = 1) { getPerformanceUseCase("1") }
        coVerify(exactly = 1) { getActiveStrategyCountUseCase("1") }
        coVerify(exactly = 1) { getPortfolioCircuitBreakerStatusUseCase("1") }
        coVerify(exactly = 1) { getRiskStatusUseCase("1") }
        coVerify(exactly = 1) { getPortfolioBrokerStatusUseCase("1") }
    }

    @Test
    fun `changing the active portfolio resets every section before reloading with the new id`() = runTest {
        coEvery { getRiskStatusUseCase("1") } returns Result.success(killSwitchRisk)
        coEvery { getPortfolioBrokerStatusUseCase("1") } returns
            Result.success<PortfolioBrokerStatus?>(PortfolioBrokerStatus("alpaca", "revoked"))
        coEvery { getActiveStrategyCountUseCase("1") } returns Result.success(3)
        coEvery { getPortfolioCircuitBreakerStatusUseCase("1") } returns
            Result.success(breaker(CircuitBreakerState.OPEN))
        createViewModel()
        viewModel.selectPeriod(PnlPeriod.WEEK)
        activityFlow.emit(catalyst())

        val before = viewModel.uiState.value
        assertEquals("1", before.portfolioId)
        assertEquals(fakeNav, before.navSummary.value)
        assertEquals(fakeCurve, before.navCurve.value)
        assertEquals(fakePerformance, before.performance)
        assertEquals(killSwitchRisk, before.riskStatus)
        assertEquals(3, before.activeStrategyCount)
        assertNotNull(before.brokerStatus)
        assertNotNull(before.circuitBreakerStatus)
        assertEquals(1, viewModel.activityItems.value.size)

        // Portefeuille « 2 » : toutes les lectures restent suspendues tant que le test ne les libère pas.
        val navGate = CompletableDeferred<Result<NavSummary>>()
        val pnlGate = CompletableDeferred<Result<PnlSummary>>()
        val curveGate = CompletableDeferred<Result<NavCurve>>()
        val perfGate = CompletableDeferred<Result<PerformanceMetrics>>()
        val riskGate = CompletableDeferred<Result<RiskStatus>>()
        val brokerGate = CompletableDeferred<Result<PortfolioBrokerStatus?>>()
        val countGate = CompletableDeferred<Result<Int>>()
        val breakerGate = CompletableDeferred<Result<PortfolioCircuitBreakerStatus>>()
        coEvery { getPortfolioNavUseCase("2") } coAnswers { navGate.await() }
        coEvery { getPnlUseCase("2", any()) } coAnswers { pnlGate.await() }
        coEvery { getNavCurveUseCase("2", any()) } coAnswers { curveGate.await() }
        coEvery { getPerformanceUseCase("2") } coAnswers { perfGate.await() }
        coEvery { getRiskStatusUseCase("2") } coAnswers { riskGate.await() }
        coEvery { getPortfolioBrokerStatusUseCase("2") } coAnswers { brokerGate.await() }
        coEvery { getActiveStrategyCountUseCase("2") } coAnswers { countGate.await() }
        coEvery { getPortfolioCircuitBreakerStatusUseCase("2") } coAnswers { breakerGate.await() }

        activePortfolio.value = "2"

        // Reset AVANT le rechargement : rien de l'ancien portefeuille n'est visible.
        val reset = viewModel.uiState.value
        assertEquals("2", reset.portfolioId)
        assertNull(reset.navSummary.value)
        assertTrue(reset.navSummary.isInitialLoading)
        assertNull(reset.pnlSummary.value)
        assertTrue(reset.pnlSummary.isInitialLoading)
        assertNull(reset.navCurve.value)
        assertNull(reset.performance)
        assertNull(reset.riskStatus)
        assertNull(reset.brokerStatus)
        assertNull(reset.activeStrategyCount)
        assertNull(reset.circuitBreakerStatus)
        assertTrue(viewModel.activityItems.value.isEmpty())
        // La période choisie est une préférence de l'utilisateur : elle survit au changement.
        assertEquals(PnlPeriod.WEEK, reset.selectedPeriod)

        // Rechargement avec le nouvel id (et la période conservée).
        navGate.complete(Result.success(fakeNav2))
        pnlGate.complete(Result.success(fakePnl2))
        curveGate.complete(Result.success(NavCurve(emptyList())))
        perfGate.complete(Result.success(fakePerformance.copy(winRate = 0.4)))
        riskGate.complete(Result.success(quietRisk))
        brokerGate.complete(Result.success<PortfolioBrokerStatus?>(null))
        countGate.complete(Result.success(1))
        breakerGate.complete(Result.success(breaker(CircuitBreakerState.CLOSED)))

        val after = viewModel.uiState.value
        assertEquals(fakeNav2, after.navSummary.value)
        assertEquals(fakePnl2, after.pnlSummary.value)
        assertEquals(0.4, after.performance!!.winRate!!, 0.0)
        assertEquals(quietRisk, after.riskStatus)
        assertNull(after.brokerStatus)
        assertEquals(1, after.activeStrategyCount)
        coVerify(exactly = 1) { getPnlUseCase("2", PnlPeriod.WEEK) }
        coVerify(exactly = 1) { getNavCurveUseCase("2", PnlPeriod.WEEK) }
    }

    @Test
    fun `a slow response for the previous portfolio never shows under the new one`() = runTest {
        val slowNav1 = CompletableDeferred<Result<NavSummary>>()
        val wsFlow = MutableSharedFlow<WsUpdate.PortfolioUpdate>()
        every { getPortfolioWsUpdatesUseCase() } returns wsFlow
        createViewModel()
        // Un refetch WS de « 1 » part puis reste en vol ; le portefeuille change avant sa réponse.
        coEvery { getPortfolioNavUseCase("1") } coAnswers { slowNav1.await() }
        coEvery { getPortfolioNavUseCase("2") } returns Result.success(fakeNav2)
        wsFlow.emit(WsUpdate.PortfolioUpdate(portfolioId = "1", totalValue = 101_000.0))
        advanceTimeBy(WS_REFETCH_DEBOUNCE_MS + 1)

        activePortfolio.value = "2"
        slowNav1.complete(
            Result.success(fakeNav.copy(currentValue = BigDecimal("999999.00"))),
        )

        val nav = viewModel.uiState.value.navSummary
        assertEquals("2", viewModel.uiState.value.portfolioId)
        assertEquals(fakeNav2, nav.value)
        assertNull(nav.error)
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
    }

    // ── PnL (DataState) ───────────────────────────────────────────────────────

    @Test
    fun `pnlSummary holds the value when use case returns data`() = runTest {
        createViewModel()
        val state = viewModel.uiState.value.pnlSummary
        assertEquals(fakePnl, state.value)
        assertNull(state.error)
        assertFalse(state.isRefreshing)
    }

    @Test
    fun `initial PnL failure leaves value null with error`() = runTest {
        coEvery { getPnlUseCase(any(), any()) } returns Result.failure(RuntimeException("PnL error"))
        createViewModel()
        val state = viewModel.uiState.value.pnlSummary
        assertNull(state.value)
        assertEquals("PnL error", state.error)
        assertFalse(state.isInitialLoading)
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
    }

    // ── Courbe de NAV ─────────────────────────────────────────────────────────

    @Test
    fun `the nav curve is loaded for the selected period`() = runTest {
        createViewModel()

        val curve = viewModel.uiState.value.navCurve
        assertEquals(fakeCurve, curve.value)
        assertFalse(curve.isRefreshing)
        assertEquals(
            listOf("100000.00", "104500.00"),
            navCurveValues(curve.value).map { it.toPlainString() },
        )
        coVerify(exactly = 1) { getNavCurveUseCase("1", PnlPeriod.DAY) }
    }

    @Test
    fun `a failed nav curve leaves no curve and does not break the rest of the screen`() = runTest {
        coEvery { getNavCurveUseCase(any(), any()) } returns Result.failure(IOException("boom"))

        createViewModel()

        val state = viewModel.uiState.value
        assertNull(state.navCurve.value)
        assertFalse(state.navCurve.isInitialLoading)
        assertEquals(emptyList<BigDecimal>(), navCurveValues(state.navCurve.value))
        assertEquals(fakeNav, state.navSummary.value)
        assertEquals(fakePnl, state.pnlSummary.value)
    }

    @Test
    fun `an empty nav curve is a success with nothing to draw`() = runTest {
        coEvery { getNavCurveUseCase(any(), any()) } returns Result.success(NavCurve(emptyList()))

        createViewModel()

        assertEquals(NavCurve(emptyList()), viewModel.uiState.value.navCurve.value)
        assertEquals(emptyList<BigDecimal>(), navCurveValues(viewModel.uiState.value.navCurve.value))
    }

    // ── KPI depuis la performance ─────────────────────────────────────────────

    @Test
    fun `win rate and drawdown tiles come from the performance metrics`() = runTest {
        createViewModel()

        val state = viewModel.uiState.value
        assertEquals(fakePerformance, state.performance)
        val kpis = dashboardKpis(state.navSummary.value, state.performance)
        assertEquals(listOf("Liquidités", "Latent", "Win rate", "Drawdown max"), kpis.map { it.label })
        // fakePnl.winRate est null (le P&L n'en fournit jamais) : c'est bien la performance qui alimente les tuiles.
        assertEquals(listOf("62%", "8,30%"), kpis.takeLast(2).map { it.value })
    }

    @Test
    fun `a failed performance read omits the win rate and drawdown tiles`() = runTest {
        coEvery { getPerformanceUseCase(any()) } returns Result.failure(IOException("boom"))

        createViewModel()

        val state = viewModel.uiState.value
        assertNull(state.performance)
        val kpis = dashboardKpis(state.navSummary.value, state.performance)
        assertEquals(listOf("Liquidités", "Latent"), kpis.map { it.label })
    }

    // ── Bandeau de risque ─────────────────────────────────────────────────────

    @Test
    fun `an active kill switch makes the risk banner appear`() = runTest {
        coEvery { getRiskStatusUseCase(any()) } returns Result.success(killSwitchRisk)

        createViewModel()

        val state = viewModel.uiState.value
        assertEquals(killSwitchRisk, state.riskStatus)
        val banner = riskBannerModel(state.riskStatus, state.circuitBreakerStatus)
        assertEquals(RiskBannerKind.KILL_SWITCH, banner!!.kind)
        assertEquals("Trading suspendu — kill switch actif", banner.title)
    }

    @Test
    fun `a quiet risk status keeps the banner hidden`() = runTest {
        coEvery { getRiskStatusUseCase(any()) } returns Result.success(quietRisk)
        coEvery { getPortfolioCircuitBreakerStatusUseCase(any()) } returns
            Result.success(breaker(CircuitBreakerState.CLOSED))

        createViewModel()

        val state = viewModel.uiState.value
        assertEquals(quietRisk, state.riskStatus)
        assertNull(riskBannerModel(state.riskStatus, state.circuitBreakerStatus))
    }

    @Test
    fun `an unavailable risk read never shows a banner`() = runTest {
        createViewModel() // risk + circuit-breaker en échec (setUp)

        val state = viewModel.uiState.value
        assertNull(state.riskStatus)
        assertNull(riskBannerModel(state.riskStatus, state.circuitBreakerStatus))
    }

    @Test
    fun `an open circuit breaker and a kill switch give a single banner, the kill switch`() = runTest {
        coEvery { getRiskStatusUseCase(any()) } returns Result.success(killSwitchRisk.copy(unresolvedViolations = 4))
        coEvery { getPortfolioCircuitBreakerStatusUseCase(any()) } returns
            Result.success(breaker(CircuitBreakerState.OPEN))

        createViewModel()

        val state = viewModel.uiState.value
        assertEquals(
            RiskBannerKind.KILL_SWITCH,
            riskBannerModel(state.riskStatus, state.circuitBreakerStatus)!!.kind,
        )
    }

    @Test
    fun `a failed risk refresh keeps the last known alert instead of hiding it`() = runTest {
        coEvery { getRiskStatusUseCase(any()) } returnsMany listOf(
            Result.success(killSwitchRisk),
            Result.failure(IOException("timeout")),
        )
        createViewModel()

        viewModel.refresh()

        assertEquals(killSwitchRisk, viewModel.uiState.value.riskStatus)
        coVerify(exactly = 2) { getRiskStatusUseCase("1") }
    }

    // ── Broker et stratégies ──────────────────────────────────────────────────

    @Test
    fun `the broker connection and the active strategy count are exposed`() = runTest {
        coEvery { getPortfolioBrokerStatusUseCase(any()) } returns
            Result.success<PortfolioBrokerStatus?>(PortfolioBrokerStatus("alpaca", "error"))
        coEvery { getActiveStrategyCountUseCase(any()) } returns Result.success(3)

        createViewModel()

        val state = viewModel.uiState.value
        assertEquals(PortfolioBrokerStatus("alpaca", "error"), state.brokerStatus)
        assertEquals("Broker : alpaca — connexion en erreur", brokerPillModel(state.brokerStatus)!!.label)
        assertEquals(3, state.activeStrategyCount)
        assertEquals("3 actives", strategiesEntryModel(state.activeStrategyCount)!!.value)
    }

    @Test
    fun `no broker connection leaves the pill absent`() = runTest {
        coEvery { getPortfolioBrokerStatusUseCase(any()) } returns Result.success<PortfolioBrokerStatus?>(null)

        createViewModel()

        assertNull(viewModel.uiState.value.brokerStatus)
        assertNull(brokerPillModel(viewModel.uiState.value.brokerStatus))
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
    }

    @Test
    fun `WS update of the active portfolio id is applied like an untagged one`() = runTest {
        val wsFlow = MutableSharedFlow<WsUpdate.PortfolioUpdate>()
        every { getPortfolioWsUpdatesUseCase() } returns wsFlow
        createViewModel()

        wsFlow.emit(WsUpdate.PortfolioUpdate(portfolioId = "1", totalValue = 105_000.0))

        val nav = viewModel.uiState.value.navSummary
        assertEquals(0, BigDecimal("105000").compareTo(nav.value!!.currentValue))
        assertTrue(nav.isRefreshing)
        advanceTimeBy(WS_REFETCH_DEBOUNCE_MS + 1)
        coVerify(exactly = 2) { getPortfolioNavUseCase("1") }
    }

    @Test
    fun `WS update of another portfolio is ignored, no patch and no refetch`() = runTest {
        val wsFlow = MutableSharedFlow<WsUpdate.PortfolioUpdate>()
        every { getPortfolioWsUpdatesUseCase() } returns wsFlow
        createViewModel()

        wsFlow.emit(WsUpdate.PortfolioUpdate(portfolioId = "2", totalValue = 999_000.0, cashBalance = 1.0))
        advanceTimeBy(WS_REFETCH_DEBOUNCE_MS + 1)

        val state = viewModel.uiState.value
        assertEquals(fakeNav, state.navSummary.value)
        assertFalse(state.navSummary.isRefreshing)
        assertFalse(state.pnlSummary.isRefreshing)
        coVerify(exactly = 1) { getPortfolioNavUseCase(any()) }
        coVerify(exactly = 1) { getPnlUseCase(any(), any()) }
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
    }

    @Test
    fun `selectPeriod resets only the pnl and the curve, the NAV keeps its value`() = runTest {
        createViewModel()
        val pnlGate = CompletableDeferred<Result<PnlSummary>>()
        val curveGate = CompletableDeferred<Result<NavCurve>>()
        val monthCurve = NavCurve(
            listOf(
                NavPoint(Instant.parse("2026-09-01T00:00:00Z"), BigDecimal("98000.00")),
                NavPoint(Instant.parse("2026-09-29T00:00:00Z"), BigDecimal("100000.00")),
            ),
        )
        coEvery { getPnlUseCase("1", PnlPeriod.MONTH) } coAnswers { pnlGate.await() }
        coEvery { getNavCurveUseCase("1", PnlPeriod.MONTH) } coAnswers { curveGate.await() }

        viewModel.selectPeriod(PnlPeriod.MONTH)

        val loading = viewModel.uiState.value
        // Ni le P&L ni la courbe du jour ne restent affichés sous la puce « Mois ».
        assertNull(loading.pnlSummary.value)
        assertTrue(loading.pnlSummary.isInitialLoading)
        assertNull(loading.navCurve.value)
        assertTrue(loading.navCurve.isInitialLoading)
        // La NAV n'est pas une donnée de période : elle garde sa valeur (pas de skeleton global).
        assertEquals(fakeNav, loading.navSummary.value)
        assertFalse(loading.navSummary.isRefreshing)

        pnlGate.complete(Result.success(fakePnl2))
        curveGate.complete(Result.success(monthCurve))
        assertEquals(fakePnl2, viewModel.uiState.value.pnlSummary.value)
        assertEquals(monthCurve, viewModel.uiState.value.navCurve.value)
        coVerify(exactly = 1) { getNavCurveUseCase("1", PnlPeriod.MONTH) }
    }

    @Test
    fun `the all period is requested for pnl and curve`() = runTest {
        createViewModel()

        viewModel.selectPeriod(PnlPeriod.ALL)

        coVerify(exactly = 1) { getPnlUseCase("1", PnlPeriod.ALL) }
        coVerify(exactly = 1) { getNavCurveUseCase("1", PnlPeriod.ALL) }
    }

    @Test
    fun `a failed pnl after a period change never shows the previous period value`() = runTest {
        createViewModel()
        coEvery { getPnlUseCase("1", PnlPeriod.WEEK) } returns Result.failure(IOException("week down"))

        viewModel.selectPeriod(PnlPeriod.WEEK)

        val pnl = viewModel.uiState.value.pnlSummary
        assertNull("the day pnl must not stay under the week chip", pnl.value)
        assertEquals("week down", pnl.error)
    }

    // ── Mes portefeuilles ─────────────────────────────────────────────────────

    @Test
    fun `a single portfolio account never requests the overview`() = runTest {
        portfolios.value = listOf(Portfolio(id = "1", name = "Principal", currency = "EUR"))

        createViewModel()

        coVerify(exactly = 0) { getPortfoliosOverviewUseCase(any()) }
        assertNull(viewModel.uiState.value.portfolioOverview.value)
    }

    @Test
    fun `an account with several portfolios loads the overview for the current period`() = runTest {
        portfolios.value = twoPortfolios

        createViewModel()

        coVerify(exactly = 1) { getPortfoliosOverviewUseCase(PnlPeriod.DAY) }
        assertEquals(overviewDay, viewModel.uiState.value.portfolioOverview.value)
    }

    @Test
    fun `the overview loads when the portfolio list arrives after startup`() = runTest {
        createViewModel()
        coVerify(exactly = 0) { getPortfoliosOverviewUseCase(any()) }

        portfolios.value = twoPortfolios

        coVerify(exactly = 1) { getPortfoliosOverviewUseCase(PnlPeriod.DAY) }
        assertEquals(overviewDay, viewModel.uiState.value.portfolioOverview.value)
    }

    @Test
    fun `renaming a portfolio does not reload the overview`() = runTest {
        portfolios.value = twoPortfolios
        createViewModel()

        portfolios.value = listOf(twoPortfolios[0].copy(name = "Renommé"), twoPortfolios[1])

        coVerify(exactly = 1) { getPortfoliosOverviewUseCase(any()) }
    }

    @Test
    fun `changing the period reloads the overview and never shows the old period pnl`() = runTest {
        portfolios.value = twoPortfolios
        createViewModel()
        val weekGate = CompletableDeferred<Result<List<PortfolioOverviewItem>>>()
        coEvery { getPortfoliosOverviewUseCase(PnlPeriod.WEEK) } coAnswers { weekGate.await() }

        viewModel.selectPeriod(PnlPeriod.WEEK)

        val loading = viewModel.uiState.value.portfolioOverview
        assertNull("day pnl must not stay under the week caption", loading.value)
        assertTrue(loading.isInitialLoading)

        weekGate.complete(Result.success(overviewWeek))
        assertEquals(overviewWeek, viewModel.uiState.value.portfolioOverview.value)
        coVerify(exactly = 1) { getPortfoliosOverviewUseCase(PnlPeriod.WEEK) }
    }

    @Test
    fun `the overview survives an active portfolio change, it is account level`() = runTest {
        portfolios.value = twoPortfolios
        createViewModel()

        activePortfolio.value = "2"

        assertEquals("2", viewModel.uiState.value.portfolioId)
        assertEquals(overviewDay, viewModel.uiState.value.portfolioOverview.value)
        coVerify(exactly = 1) { getPortfoliosOverviewUseCase(any()) }
    }

    @Test
    fun `a failed overview leaves no value and no card`() = runTest {
        coEvery { getPortfoliosOverviewUseCase(any()) } returns Result.failure(IOException("VPN down"))
        portfolios.value = twoPortfolios

        createViewModel()

        val overview = viewModel.uiState.value.portfolioOverview
        assertNull(overview.value)
        assertEquals("VPN down", overview.error)
        assertFalse(
            shouldShowPortfolioOverview(
                portfolioCount = viewModel.portfolios.value.size,
                hasValue = overview.value != null,
                isLoading = overview.isInitialLoading,
            ),
        )
    }

    @Test
    fun `losing the second portfolio clears the overview`() = runTest {
        portfolios.value = twoPortfolios
        createViewModel()

        portfolios.value = listOf(twoPortfolios[0])

        assertNull(viewModel.uiState.value.portfolioOverview.value)
    }

    @Test
    fun `selecting a portfolio row delegates to the use case`() = runTest {
        createViewModel()

        viewModel.selectPortfolio("2")

        coVerify(exactly = 1) { selectPortfolioUseCase("2") }
    }

    @Test
    fun `a failed selection does not crash`() = runTest {
        coEvery { selectPortfolioUseCase(any()) } returns Result.failure(IllegalArgumentException("unknown"))
        createViewModel()

        viewModel.selectPortfolio("999")

        coVerify(exactly = 1) { selectPortfolioUseCase("999") }
        assertEquals("1", viewModel.uiState.value.portfolioId)
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
    }

    // ── refresh ───────────────────────────────────────────────────────────────

    @Test
    fun `refresh triggers re-fetch of all data`() = runTest {
        createViewModel()

        // All use cases already return success
        viewModel.refresh()

        val state = viewModel.uiState.value
        // La valeur n'est jamais remise à null pendant / après un refresh réussi
        assertEquals(fakeNav, state.navSummary.value)
        assertEquals(fakePnl, state.pnlSummary.value)
        coVerify(exactly = 2) { getPortfolioNavUseCase(any()) }
        coVerify(exactly = 2) { getPnlUseCase(any(), any()) }
        coVerify(exactly = 2) { getNavCurveUseCase(any(), any()) }
        coVerify(exactly = 2) { getPerformanceUseCase(any()) }
        coVerify(exactly = 2) { getRiskStatusUseCase(any()) }
        coVerify(exactly = 2) { getPortfolioBrokerStatusUseCase(any()) }
        coVerify(exactly = 2) { getActiveStrategyCountUseCase(any()) }
    }

    @Test
    fun `refresh retries the portfolio list while it is still empty`() = runTest {
        createViewModel()
        coVerify(exactly = 1) { refreshPortfoliosUseCase() }

        viewModel.refresh()

        coVerify(exactly = 2) { refreshPortfoliosUseCase() }
    }

    @Test
    fun `refresh without a known active portfolio only retries the portfolio list`() = runTest {
        every { observeActivePortfolioUseCase() } returns emptyFlow()
        createViewModel()
        assertEquals("", viewModel.uiState.value.portfolioId)

        viewModel.refresh()

        coVerify(exactly = 2) { refreshPortfoliosUseCase() }
        coVerify(exactly = 0) { getPortfolioNavUseCase(any()) }
        coVerify(exactly = 0) { getPnlUseCase(any(), any()) }
    }

    @Test
    fun `refresh reloads the overview of a multi portfolio account and keeps the list as is`() = runTest {
        portfolios.value = twoPortfolios
        createViewModel()

        viewModel.refresh()

        coVerify(exactly = 2) { getPortfoliosOverviewUseCase(PnlPeriod.DAY) }
        coVerify(exactly = 1) { refreshPortfoliosUseCase() }
    }

    @Test
    fun `refresh after a portfolio switch uses the new id`() = runTest {
        createViewModel()

        activePortfolio.value = "2"
        viewModel.refresh()

        coVerify(exactly = 2) { getPortfolioNavUseCase("2") }
        coVerify(exactly = 1) { getPortfolioNavUseCase("1") }
    }

    @Test
    fun `refresh is ignored while the previous one is still running`() = runTest {
        createViewModel()
        val gate = CompletableDeferred<Result<NavSummary>>()
        coEvery { getPortfolioNavUseCase("1") } coAnswers { gate.await() }

        viewModel.refresh()
        viewModel.refresh()
        viewModel.refresh()

        // Un seul jeu de requêtes malgré trois demandes (le premier est encore en vol).
        coVerify(exactly = 2) { getPortfolioNavUseCase("1") }
        gate.complete(Result.success(fakeNav))
    }
}
