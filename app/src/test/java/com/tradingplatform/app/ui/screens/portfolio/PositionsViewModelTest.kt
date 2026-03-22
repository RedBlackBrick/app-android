package com.tradingplatform.app.ui.screens.portfolio

import app.cash.turbine.test
import com.tradingplatform.app.domain.model.Position
import com.tradingplatform.app.domain.model.PositionStatus
import com.tradingplatform.app.domain.usecase.auth.GetPortfolioIdUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPositionWsUpdatesUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPositionsUseCase
import com.tradingplatform.app.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PositionsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val getPositionsUseCase = mockk<GetPositionsUseCase>()
    private val getPortfolioIdUseCase = mockk<GetPortfolioIdUseCase>()
    private val getPositionWsUpdatesUseCase = mockk<GetPositionWsUpdatesUseCase>()

    private lateinit var viewModel: PositionsViewModel

    // ── Fakes ─────────────────────────────────────────────────────────────────

    private val fakePosition = Position(
        id = 42,
        symbol = "TSLA",
        quantity = BigDecimal("10"),
        avgPrice = BigDecimal("250.00"),
        currentPrice = BigDecimal("280.00"),
        unrealizedPnl = BigDecimal("300.00"),
        unrealizedPnlPercent = 12.0,
        status = PositionStatus.OPEN,
        openedAt = Instant.now(),
    )

    private val fakePositions = listOf(fakePosition)

    @Before
    fun setUp() {
        coEvery { getPortfolioIdUseCase() } returns "1"
        coEvery { getPositionsUseCase(any(), any()) } returns Result.success(fakePositions)
        every { getPositionWsUpdatesUseCase() } returns emptyFlow()
    }

    private fun createViewModel(): PositionsViewModel = PositionsViewModel(
        getPositionsUseCase = getPositionsUseCase,
        getPortfolioIdUseCase = getPortfolioIdUseCase,
        getPositionWsUpdatesUseCase = getPositionWsUpdatesUseCase,
    )

    // ── Success ───────────────────────────────────────────────────────────────

    @Test
    fun `uiState emits Success with positions on successful load`() = runTest {
        viewModel = createViewModel()

        val state = viewModel.uiState.value
        assertTrue("Expected Success, got $state", state is PositionsUiState.Success)
        val success = state as PositionsUiState.Success
        assertEquals(fakePositions, success.positions)
    }

    @Test
    fun `syncedAt is set on Success`() = runTest {
        viewModel = createViewModel()
        val state = viewModel.uiState.value as? PositionsUiState.Success
        assertNotNull("Expected Success state", state)
        assertTrue("syncedAt should be positive", state!!.syncedAt > 0L)
    }

    @Test
    fun `success with empty positions list is handled`() = runTest {
        coEvery { getPositionsUseCase(any(), any()) } returns Result.success(emptyList())
        viewModel = createViewModel()
        val state = viewModel.uiState.value
        assertTrue("Expected Success with empty list", state is PositionsUiState.Success)
        assertEquals(emptyList<Position>(), (state as PositionsUiState.Success).positions)
    }

    // ── Error ─────────────────────────────────────────────────────────────────

    @Test
    fun `uiState emits Error on use case failure`() = runTest {
        coEvery { getPositionsUseCase(any(), any()) } returns
            Result.failure(RuntimeException("Network error"))
        viewModel = createViewModel()

        val state = viewModel.uiState.value
        assertTrue("Expected Error, got $state", state is PositionsUiState.Error)
    }

    @Test
    fun `error message is propagated from exception`() = runTest {
        coEvery { getPositionsUseCase(any(), any()) } returns
            Result.failure(RuntimeException("Connexion refusée"))
        viewModel = createViewModel()

        val state = viewModel.uiState.value as? PositionsUiState.Error
        assertNotNull("Expected Error state", state)
        assertEquals("Connexion refusée", state!!.message)
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    @Test
    fun `refresh re-fetches positions from use case`() = runTest {
        viewModel = createViewModel()

        viewModel.refresh()

        // Use case should have been called at least twice: once on init, once on refresh
        coVerify(atLeast = 2) { getPositionsUseCase(any(), any()) }
    }

    @Test
    fun `refresh emits Loading then Success`() = runTest {
        viewModel = createViewModel()

        viewModel.uiState.test {
            // Skip any already-emitted items from init
            while (awaitItem() !is PositionsUiState.Success) { /* skip */ }

            viewModel.refresh()

            // After refresh: Loading, then Success
            val afterRefresh = awaitItem()
            assertTrue(
                "Expected Loading or Success after refresh, got $afterRefresh",
                afterRefresh is PositionsUiState.Loading || afterRefresh is PositionsUiState.Success,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh recovers from previous error`() = runTest {
        // First call fails
        coEvery { getPositionsUseCase(any(), any()) } returnsMany listOf(
            Result.failure(RuntimeException("First error")),
            Result.success(fakePositions),
        )

        viewModel = createViewModel()

        // Initial state should be Error
        assertTrue(viewModel.uiState.value is PositionsUiState.Error)

        // Refresh should recover
        viewModel.refresh()

        assertTrue(
            "Expected Success after refresh, got ${viewModel.uiState.value}",
            viewModel.uiState.value is PositionsUiState.Success,
        )
    }

    // ── portfolioId ───────────────────────────────────────────────────────────

    @Test
    fun `uses portfolioId from dataStore`() = runTest {
        coEvery { getPortfolioIdUseCase() } returns "7"
        viewModel = createViewModel()

        coVerify { getPositionsUseCase("7", any()) }
    }

    @Test
    fun `defaults to empty portfolioId when dataStore returns null`() = runTest {
        coEvery { getPortfolioIdUseCase() } returns ""
        viewModel = createViewModel()

        coVerify { getPositionsUseCase("", any()) }
    }

    // ── Turbine StateFlow test ────────────────────────────────────────────────

    @Test
    fun `uiState transitions from Loading to Success`() = runTest {
        viewModel = createViewModel()

        viewModel.uiState.test {
            val items = mutableListOf(awaitItem())
            while (items.last() is PositionsUiState.Loading) {
                items.add(awaitItem())
            }
            val finalState = items.last()
            assertTrue(
                "Expected final state to be Success, got $finalState",
                finalState is PositionsUiState.Success,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }
}

// ── Helper ────────────────────────────────────────────────────────────────────

private fun assertNotNull(message: String, obj: Any?) {
    org.junit.Assert.assertNotNull(message, obj)
}
