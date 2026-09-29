package com.tradingplatform.app.ui.screens.portfolio

import androidx.lifecycle.SavedStateHandle
import com.tradingplatform.app.domain.model.Cached
import com.tradingplatform.app.domain.model.Position
import com.tradingplatform.app.domain.model.PositionStatus
import com.tradingplatform.app.domain.model.Transaction
import com.tradingplatform.app.domain.usecase.portfolio.GetPositionUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetTransactionsUseCase
import com.tradingplatform.app.domain.usecase.portfolio.ObserveActivePortfolioUseCase
import com.tradingplatform.app.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
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

    private val getPositionUseCase = mockk<GetPositionUseCase>()
    private val getTransactionsUseCase = mockk<GetTransactionsUseCase>()
    private val observeActivePortfolioUseCase = mockk<ObserveActivePortfolioUseCase>()

    /** Portefeuille actif simulé : modifier `.value` équivaut à un changement de sélection. */
    private val activePortfolio = MutableStateFlow("1")

    private val fakePositionId = 42

    /** Horodatage réel de la donnée (cache Room) — distinct de l'instant d'affichage. */
    private val fakeSyncedAt = 1_700_000_000_000L

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
        every { observeActivePortfolioUseCase() } returns activePortfolio
        coEvery { getPositionUseCase(any(), any(), any()) } returns Result.success(Cached(fakePosition, fakeSyncedAt))
        coEvery { getTransactionsUseCase(any(), any(), any(), any()) } returns
            Result.success(listOf(fakeTransaction))
    }

    private fun createViewModel(): PositionDetailViewModel = PositionDetailViewModel(
        getPositionUseCase = getPositionUseCase,
        getTransactionsUseCase = getTransactionsUseCase,
        observeActivePortfolioUseCase = observeActivePortfolioUseCase,
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

    @Test
    fun `uiState Success exposes the real syncedAt of the cached position, not now`() = runTest {
        val viewModel = createViewModel()

        val state = viewModel.uiState.value as? PositionDetailUiState.Success
        assertNotNull("Expected Success state", state)
        assertEquals(fakeSyncedAt, state!!.syncedAt)
    }

    @Test
    fun `uiState Success keeps the real syncedAt when transactions fail`() = runTest {
        coEvery { getTransactionsUseCase(any(), any(), any(), any()) } returns
            Result.failure(RuntimeException("Transactions unavailable"))

        val viewModel = createViewModel()

        val state = viewModel.uiState.value as? PositionDetailUiState.Success
        assertNotNull("Expected Success state", state)
        assertEquals(fakeSyncedAt, state!!.syncedAt)
    }

    // ── Error — position not found ─────────────────────────────────────────────

    @Test
    fun `uiState emits Error when position is not found`() = runTest {
        // GetPositionUseCase returns Result<Position> — failure means position not found
        coEvery { getPositionUseCase(any(), any(), any()) } returns
            Result.failure(NoSuchElementException("Position introuvable"))

        val viewModel = createViewModel()

        val state = viewModel.uiState.value
        assertTrue("Expected Error, got $state", state is PositionDetailUiState.Error)
        val error = state as PositionDetailUiState.Error
        assertEquals("Position introuvable", error.message)
    }

    @Test
    fun `uiState emits Error when getPositionUseCase fails`() = runTest {
        coEvery { getPositionUseCase(any(), any(), any()) } returns
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
        coVerify(atLeast = 2) { getPositionUseCase(any(), any(), any()) }
    }

    @Test
    fun `initial load may use the cache and refresh forces a network fetch`() = runTest {
        val viewModel = createViewModel()

        coVerify(exactly = 1) { getPositionUseCase("1", fakePositionId, false) }
        coVerify(exactly = 0) { getPositionUseCase(any(), any(), true) }

        viewModel.refresh()

        coVerify(exactly = 1) { getPositionUseCase("1", fakePositionId, true) }
    }

    @Test
    fun `refresh recovers from previous error`() = runTest {
        coEvery { getPositionUseCase(any(), any(), any()) } returnsMany listOf(
            Result.failure(RuntimeException("First error")),
            Result.success(Cached(fakePosition, fakeSyncedAt)),
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

    // ── Portefeuille actif ────────────────────────────────────────────────────

    @Test
    fun `uses the active portfolio id`() = runTest {
        activePortfolio.value = "7"
        val viewModel = createViewModel()

        coVerify { getPositionUseCase("7", fakePositionId, false) }
        coVerify { getTransactionsUseCase("7", any(), any(), "TSLA") }
    }

    @Test
    fun `stays Loading without fetching until an active portfolio is known`() = runTest {
        val raw = MutableStateFlow<String?>(null)
        every { observeActivePortfolioUseCase() } returns raw.filterNotNull()

        val viewModel = createViewModel()

        assertTrue(viewModel.uiState.value is PositionDetailUiState.Loading)
        coVerify(exactly = 0) { getPositionUseCase(any(), any(), any()) }

        viewModel.refresh() // toujours en attente : aucun appel avec un id vide
        coVerify(exactly = 0) { getPositionUseCase(any(), any(), any()) }
    }

    @Test
    fun `reloading reads the active portfolio current at that time`() = runTest {
        activePortfolio.value = "p1"
        val viewModel = createViewModel()
        coVerify(exactly = 1) { getPositionUseCase("p1", fakePositionId, false) }

        // Pas de collecte continue : le changement est pris en compte au rechargement suivant.
        activePortfolio.value = "p2"
        coVerify(exactly = 0) { getPositionUseCase("p2", any(), any()) }

        viewModel.refresh()

        coVerify(exactly = 1) { getPositionUseCase("p2", fakePositionId, true) }
        coVerify(exactly = 1) { getTransactionsUseCase("p2", any(), any(), "TSLA") }
        assertTrue(viewModel.uiState.value is PositionDetailUiState.Success)
    }
}
