package com.tradingplatform.app.ui.screens.performance

import app.cash.turbine.test
import com.tradingplatform.app.domain.model.PerformanceMetrics
import com.tradingplatform.app.domain.usecase.portfolio.GetPerformanceUseCase
import com.tradingplatform.app.domain.usecase.portfolio.ObserveActivePortfolioUseCase
import com.tradingplatform.app.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class PerformanceViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val getPerformanceUseCase = mockk<GetPerformanceUseCase>()
    private val observeActivePortfolioUseCase = mockk<ObserveActivePortfolioUseCase>()

    /** Portefeuille actif simulé : modifier `.value` équivaut à un changement de sélection. */
    private val activePortfolio = MutableStateFlow("42")

    /**
     * `maxDrawdown` is already converted to a **fraction** by `PerformanceResponseDto.toPerformanceMetrics()`
     * (backend sends 8.3 as a percent → mapper divides by 100 → 0.083, see Mappers.kt). The UI
     * (`PerformanceScreen`) multiplies by 100 again at display time to render "8,30 %". This test
     * asserts on the raw state value the ViewModel exposes — not on any formatting — per the task
     * instructions.
     */
    private val fakeMetrics = PerformanceMetrics(
        totalReturn = BigDecimal("1250.00"),
        totalReturnPct = 0.125,
        sharpeRatio = 1.4,
        sortinoRatio = 1.8,
        maxDrawdown = 0.083,
        volatility = 0.15,
        cagr = 0.11,
        winRate = 0.62,
        profitFactor = 1.9,
        avgTradeReturn = BigDecimal("42.50"),
    )

    @Before
    fun setUp() {
        every { observeActivePortfolioUseCase() } returns activePortfolio
    }

    private fun createViewModel(): PerformanceViewModel = PerformanceViewModel(
        getPerformanceUseCase = getPerformanceUseCase,
        observeActivePortfolioUseCase = observeActivePortfolioUseCase,
    )

    // ── init ─────────────────────────────────────────────────────────────────

    @Test
    fun `uiState emits Success with the metrics exposed unchanged (maxDrawdown stays a fraction)`() = runTest {
        coEvery { getPerformanceUseCase("42") } returns Result.success(fakeMetrics)

        val viewModel = createViewModel()

        viewModel.uiState.test {
            val state = awaitItem()
            assertIs<PerformanceUiState.Success>(state)
            assertEquals(fakeMetrics, state.metrics)
            // Explicit on the field called out by the task — no ×100 applied in the ViewModel.
            assertEquals(0.083, state.metrics.maxDrawdown!!, 0.0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `uiState emits Error when GetPerformanceUseCase fails`() = runTest {
        coEvery { getPerformanceUseCase("42") } returns Result.failure(RuntimeException("VPN down"))

        val viewModel = createViewModel()

        viewModel.uiState.test {
            val state = awaitItem()
            assertIs<PerformanceUiState.Error>(state)
            assertEquals("VPN down", state.message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `uiState uses the fallback message when the failure has no localized message`() = runTest {
        coEvery { getPerformanceUseCase("42") } returns Result.failure(RuntimeException())

        val viewModel = createViewModel()

        viewModel.uiState.test {
            val state = awaitItem()
            assertIs<PerformanceUiState.Error>(state)
            assertEquals("Erreur lors du chargement des performances", state.message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── refresh ──────────────────────────────────────────────────────────────

    @Test
    fun `refresh re-fetches performance for the resolved portfolioId`() = runTest {
        coEvery { getPerformanceUseCase("42") } returns Result.success(fakeMetrics)

        val viewModel = createViewModel()
        viewModel.refresh()

        coVerify(exactly = 2) { getPerformanceUseCase("42") }
        // Le flux du portefeuille actif est collecté une seule fois (init) — refresh() ne le relit pas.
        verify(exactly = 1) { observeActivePortfolioUseCase() }
    }

    @Test
    fun `refresh is a no-op when portfolioId has not resolved yet`() = runTest {
        // Portfolio id resolves to empty synchronously in this test setup (UnconfinedTestDispatcher),
        // so this exercises the defensive early-return guard directly.
        activePortfolio.value = ""
        coEvery { getPerformanceUseCase(any()) } returns Result.success(fakeMetrics)

        val viewModel = createViewModel()
        viewModel.refresh()

        // init() already attempted a fetch with the empty id once; refresh() must not add another.
        coVerify(exactly = 1) { getPerformanceUseCase("") }
    }

    @Test
    fun `refresh recovers to Success after a prior failure`() = runTest {
        coEvery { getPerformanceUseCase("42") } returns Result.failure(RuntimeException("timeout"))
        val viewModel = createViewModel()

        coEvery { getPerformanceUseCase("42") } returns Result.success(fakeMetrics)
        viewModel.refresh()

        viewModel.uiState.test {
            val state = awaitItem()
            assertIs<PerformanceUiState.Success>(state)
            assertEquals(fakeMetrics, state.metrics)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Changement de portefeuille actif ─────────────────────────────────────

    @Test
    fun `changing the active portfolio resets to Loading then reloads with the new id`() = runTest {
        val gate = CompletableDeferred<Result<PerformanceMetrics>>()
        val p2Metrics = fakeMetrics.copy(sharpeRatio = 2.5, totalReturnPct = 0.31)
        coEvery { getPerformanceUseCase("p1") } returns Result.success(fakeMetrics)
        coEvery { getPerformanceUseCase("p2") } coAnswers { gate.await() }
        activePortfolio.value = "p1"
        val viewModel = createViewModel()
        assertEquals(fakeMetrics, (viewModel.uiState.value as PerformanceUiState.Success).metrics)

        activePortfolio.value = "p2"

        // Les métriques de p1 ne doivent plus être visibles sous p2 pendant le rechargement.
        assertIs<PerformanceUiState.Loading>(viewModel.uiState.value)
        gate.complete(Result.success(p2Metrics))

        val state = viewModel.uiState.value
        assertIs<PerformanceUiState.Success>(state)
        assertEquals(p2Metrics, state.metrics)
        coVerify(exactly = 1) { getPerformanceUseCase("p2") }
    }

    @Test
    fun `a late response for the previous portfolio never reaches the screen`() = runTest {
        val lateP1 = CompletableDeferred<Result<PerformanceMetrics>>()
        val p2Metrics = fakeMetrics.copy(sharpeRatio = 2.5)
        coEvery { getPerformanceUseCase("p1") } coAnswers { lateP1.await() }
        coEvery { getPerformanceUseCase("p2") } returns Result.success(p2Metrics)
        activePortfolio.value = "p1"
        val viewModel = createViewModel() // le chargement de p1 est suspendu

        activePortfolio.value = "p2"
        lateP1.complete(Result.success(fakeMetrics)) // réponse tardive de p1

        val state = viewModel.uiState.value
        assertIs<PerformanceUiState.Success>(state)
        assertEquals(p2Metrics, state.metrics)
    }

    @Test
    fun `refresh after a portfolio switch uses the new id`() = runTest {
        coEvery { getPerformanceUseCase(any()) } returns Result.success(fakeMetrics)
        val viewModel = createViewModel()

        activePortfolio.value = "p2"
        viewModel.refresh()

        coVerify(exactly = 2) { getPerformanceUseCase("p2") }
        coVerify(exactly = 1) { getPerformanceUseCase("42") }
    }
}
