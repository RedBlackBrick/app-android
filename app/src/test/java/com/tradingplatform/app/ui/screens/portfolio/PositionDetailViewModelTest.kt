package com.tradingplatform.app.ui.screens.portfolio

import androidx.lifecycle.SavedStateHandle
import com.tradingplatform.app.domain.model.Position
import com.tradingplatform.app.domain.model.PositionStatus
import com.tradingplatform.app.domain.model.Transaction
import com.tradingplatform.app.domain.usecase.auth.GetPortfolioIdUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPositionsUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetTransactionsUseCase
import com.tradingplatform.app.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class PositionDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val getPositionsUseCase = mockk<GetPositionsUseCase>()
    private val getTransactionsUseCase = mockk<GetTransactionsUseCase>()
    private val getPortfolioIdUseCase = mockk<GetPortfolioIdUseCase>()

    private val fakePositionId = 42

    private val fakePosition = Position(
        id = fakePositionId,
        symbol = "TSLA",
        quantity = BigDecimal("10"),
        avgPrice = BigDecimal("250.00"),
        currentPrice = BigDecimal("280.00"),
        unrealizedPnl = BigDecimal("300.00"),
        unrealizedPnlPercent = 12.0,
        status = PositionStatus.OPEN,
        openedAt = Instant.now(),
    )

    private val fakeTransaction = Transaction(
        id = 1L,
        symbol = "TSLA",
        action = "BUY",
        quantity = BigDecimal("10"),
        price = BigDecimal("250.00"),
        commission = BigDecimal("1.00"),
        total = BigDecimal("2501.00"),
        executedAt = Instant.now(),
    )

    private val savedStateHandle = SavedStateHandle(mapOf("positionId" to fakePositionId))

    @Before
    fun setUp() {
        coEvery { getPortfolioIdUseCase() } returns 1
        coEvery { getPositionsUseCase(any(), any()) } returns Result.success(listOf(fakePosition))
        coEvery { getTransactionsUseCase(any(), any(), any(), any()) } returns
            Result.success(listOf(fakeTransaction))
    }

    private fun createViewModel(): PositionDetailViewModel = PositionDetailViewModel(
        getPositionsUseCase = getPositionsUseCase,
        getTransactionsUseCase = getTransactionsUseCase,
        getPortfolioIdUseCase = getPortfolioIdUseCase,
        savedStateHandle = savedStateHandle,
    )

    // ── Success ───────────────────────────────────────────────────────────────

    @Test
    fun `uiState emits Success with correct position on load`() = runTest {
        val viewModel = createViewModel()

        val state = viewModel.uiState.value
        assertTrue("Expected Success, got $state", state is PositionDetailUiState.Success)
        val success = state as PositionDetailUiState.Success
        assertEquals(fakePosition, success.position)
    }

    @Test
    fun `uiState Success contains transactions`() = runTest {
        val viewModel = createViewModel()

        val state = viewModel.uiState.value as? PositionDetailUiState.Success
        assertNotNull("Expected Success state", state)
        assertEquals(listOf(fakeTransaction), state!!.transactions)
    }

    @Test
    fun `uiState Success has positive syncedAt timestamp`() = runTest {
        val viewModel = createViewModel()

        val state = viewModel.uiState.value as? PositionDetailUiState.Success
        assertNotNull("Expected Success state", state)
        assertTrue("syncedAt should be positive", state!!.syncedAt > 0L)
    }

    // ── Error — position not found ─────────────────────────────────────────────

    @Test
    fun `uiState emits Error when position is not found in list`() = runTest {
        // Return a list that does not contain the requested positionId
        val otherPosition = fakePosition.copy(id = 999)
        coEvery { getPositionsUseCase(any(), any()) } returns Result.success(listOf(otherPosition))

        val viewModel = createViewModel()

        val state = viewModel.uiState.value
        assertTrue("Expected Error, got $state", state is PositionDetailUiState.Error)
        val error = state as PositionDetailUiState.Error
        assertEquals("Position introuvable", error.message)
    }

    @Test
    fun `uiState emits Error when getPositionsUseCase fails`() = runTest {
        coEvery { getPositionsUseCase(any(), any()) } returns
            Result.failure(RuntimeException("Network error"))

        val viewModel = createViewModel()

        val state = viewModel.uiState.value
        assertTrue("Expected Error, got $state", state is PositionDetailUiState.Error)
        val error = state as PositionDetailUiState.Error
        assertEquals("Network error", error.message)
    }

    // ── Transactions failure — still shows position ──────────────────────────

    @Test
    fun `uiState emits Success with empty transactions when getTransactionsUseCase fails`() = runTest {
        coEvery { getTransactionsUseCase(any(), any(), any(), any()) } returns
            Result.failure(RuntimeException("Transactions unavailable"))

        val viewModel = createViewModel()

        val state = viewModel.uiState.value
        assertTrue("Expected Success even if transactions fail, got $state", state is PositionDetailUiState.Success)
        val success = state as PositionDetailUiState.Success
        assertEquals(fakePosition, success.position)
        assertEquals(emptyList<Transaction>(), success.transactions)
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    @Test
    fun `refresh re-fetches position from use case`() = runTest {
        val viewModel = createViewModel()

        viewModel.refresh()

        // Use case should have been called at least twice: once on init, once on refresh
        coVerify(atLeast = 2) { getPositionsUseCase(any(), any()) }
    }

    @Test
    fun `refresh recovers from previous error`() = runTest {
        coEvery { getPositionsUseCase(any(), any()) } returnsMany listOf(
            Result.failure(RuntimeException("First error")),
            Result.success(listOf(fakePosition)),
        )

        val viewModel = createViewModel()

        // Initial state should be Error
        assertTrue(viewModel.uiState.value is PositionDetailUiState.Error)

        // Refresh should recover to Success
        viewModel.refresh()

        assertTrue(
            "Expected Success after refresh, got ${viewModel.uiState.value}",
            viewModel.uiState.value is PositionDetailUiState.Success,
        )
    }

    // ── portfolioId ───────────────────────────────────────────────────────────

    @Test
    fun `uses portfolioId from use case`() = runTest {
        coEvery { getPortfolioIdUseCase() } returns 7
        val viewModel = createViewModel()

        coVerify { getPositionsUseCase(7, any()) }
    }

    @Test
    fun `defaults to portfolioId 0 when use case returns 0`() = runTest {
        coEvery { getPortfolioIdUseCase() } returns 0
        val viewModel = createViewModel()

        coVerify { getPositionsUseCase(0, any()) }
    }
}
