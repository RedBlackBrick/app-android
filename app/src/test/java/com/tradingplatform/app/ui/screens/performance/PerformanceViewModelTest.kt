package com.tradingplatform.app.ui.screens.performance

import app.cash.turbine.test
import com.tradingplatform.app.domain.model.PerformanceMetrics
import com.tradingplatform.app.domain.usecase.auth.GetPortfolioIdUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPerformanceUseCase
import com.tradingplatform.app.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    private val getPortfolioIdUseCase = mockk<GetPortfolioIdUseCase>()

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
        coEvery { getPortfolioIdUseCase() } returns "42"
    }

    private fun createViewModel(): PerformanceViewModel = PerformanceViewModel(
        getPerformanceUseCase = getPerformanceUseCase,
        getPortfolioIdUseCase = getPortfolioIdUseCase,
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
        // portfolioId is resolved once in init and cached — refresh() must not re-resolve it.
        coVerify(exactly = 1) { getPortfolioIdUseCase() }
    }

    @Test
    fun `refresh is a no-op when portfolioId has not resolved yet`() = runTest {
        // Portfolio id resolves to empty synchronously in this test setup (UnconfinedTestDispatcher),
        // so this exercises the defensive early-return guard directly.
        coEvery { getPortfolioIdUseCase() } returns ""
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
}
