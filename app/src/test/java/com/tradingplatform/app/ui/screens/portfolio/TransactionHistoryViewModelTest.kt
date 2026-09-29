package com.tradingplatform.app.ui.screens.portfolio

import com.tradingplatform.app.domain.model.Transaction
import com.tradingplatform.app.domain.usecase.portfolio.GetTransactionsUseCase
import com.tradingplatform.app.domain.usecase.portfolio.ObserveActivePortfolioUseCase
import com.tradingplatform.app.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * Covers finding C-tx-conc-1 (audit/candidates-C-ui-vpn.md): `loadMore`/`refresh` had no
 * re-entrancy guard, so a double tap on "Charger plus" duplicated a page and desynced the
 * offset, and `LazyColumn(key = { it.id })` could crash on the resulting duplicate ids.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionHistoryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val getTransactionsUseCase = mockk<GetTransactionsUseCase>()
    private val observeActivePortfolioUseCase = mockk<ObserveActivePortfolioUseCase>()

    /** Portefeuille actif simulé : modifier `.value` équivaut à un changement de sélection. */
    private val activePortfolio = MutableStateFlow("1")

    private lateinit var viewModel: TransactionHistoryViewModel

    private fun tx(id: Long): Transaction = Transaction(
        id = id,
        symbol = "TSLA",
        action = "BUY",
        quantity = BigDecimal("1"),
        price = BigDecimal("100.00"),
        commission = BigDecimal.ZERO,
        total = BigDecimal("100.00"),
        executedAt = Instant.now(),
    )

    // First page is exactly pageSize (50) so hasMore is true and loadMore() is exercised.
    private val firstPage = (1L..50L).map(::tx)

    @Before
    fun setUp() {
        every { observeActivePortfolioUseCase() } returns activePortfolio
        coEvery { getTransactionsUseCase(any(), any(), 0, any()) } returns Result.success(firstPage)
    }

    private fun createViewModel(): TransactionHistoryViewModel = TransactionHistoryViewModel(
        getTransactionsUseCase = getTransactionsUseCase,
        observeActivePortfolioUseCase = observeActivePortfolioUseCase,
    )

    @Test
    fun `initial load populates transactions with hasMore when the page is full`() = runTest {
        viewModel = createViewModel()

        val state = viewModel.uiState.value as TransactionHistoryUiState.Success
        assertEquals(50, state.transactions.size)
        assertTrue(state.hasMore)
        assertFalse(state.isLoadingMore)
    }

    @Test
    fun `double loadMore before the first completes triggers only one use-case call`() = runTest {
        viewModel = createViewModel()

        val deferred = CompletableDeferred<Result<List<Transaction>>>()
        coEvery { getTransactionsUseCase(any(), any(), 50, any()) } coAnswers { deferred.await() }

        viewModel.loadMore()
        viewModel.loadMore() // must no-op: a load is already active

        deferred.complete(Result.success((51L..60L).map(::tx)))

        coVerify(exactly = 1) { getTransactionsUseCase(any(), any(), 50, any()) }
    }

    @Test
    fun `isLoadingMore is true while the page loads and false once resolved`() = runTest {
        viewModel = createViewModel()

        val deferred = CompletableDeferred<Result<List<Transaction>>>()
        coEvery { getTransactionsUseCase(any(), any(), 50, any()) } coAnswers { deferred.await() }

        viewModel.loadMore()

        val loadingState = viewModel.uiState.value as TransactionHistoryUiState.Success
        assertTrue("Expected isLoadingMore=true while the page is in flight", loadingState.isLoadingMore)
        assertEquals(50, loadingState.transactions.size) // existing page kept visible while loading more

        deferred.complete(Result.success((51L..60L).map(::tx)))

        val resolvedState = viewModel.uiState.value as TransactionHistoryUiState.Success
        assertFalse(resolvedState.isLoadingMore)
        assertEquals(60, resolvedState.transactions.size)
    }

    @Test
    fun `loadMore appends without duplicate ids when a page overlaps`() = runTest {
        viewModel = createViewModel()

        // Server returns id 50 again at the head of the next page — a real scenario when
        // offset accounting drifts (the bug this guard prevents from crashing the LazyColumn).
        coEvery { getTransactionsUseCase(any(), any(), 50, any()) } returns
            Result.success((50L..60L).map(::tx))

        viewModel.loadMore()

        val state = viewModel.uiState.value as TransactionHistoryUiState.Success
        assertEquals(60, state.transactions.size)
        assertEquals(60, state.transactions.map { it.id }.distinct().size)
    }

    @Test
    fun `refresh cancels an in-flight loadMore and resets to the first page`() = runTest {
        viewModel = createViewModel()

        val deferred = CompletableDeferred<Result<List<Transaction>>>()
        coEvery { getTransactionsUseCase(any(), any(), 50, any()) } coAnswers { deferred.await() }

        viewModel.loadMore()
        assertTrue((viewModel.uiState.value as TransactionHistoryUiState.Success).isLoadingMore)

        viewModel.refresh()

        // The cancelled loadMore's eventual (stale) completion must not resurrect page 2 data.
        deferred.complete(Result.success((51L..60L).map(::tx)))

        val state = viewModel.uiState.value as TransactionHistoryUiState.Success
        assertEquals(50, state.transactions.size)
        assertEquals(1L, state.transactions.first().id)
    }

    @Test
    fun `error state is surfaced on use case failure`() = runTest {
        coEvery { getTransactionsUseCase(any(), any(), 0, any()) } returns
            Result.failure(RuntimeException("Network error"))

        viewModel = createViewModel()

        assertTrue(viewModel.uiState.value is TransactionHistoryUiState.Error)
    }

    // ── Changement de portefeuille actif ─────────────────────────────────────────

    @Test
    fun `changing the active portfolio resets the list and pagination then reloads with the new id`() = runTest {
        val gate = CompletableDeferred<Result<List<Transaction>>>()
        coEvery { getTransactionsUseCase("p1", any(), 0, any()) } returns Result.success(firstPage)
        coEvery { getTransactionsUseCase("p2", any(), 0, any()) } coAnswers { gate.await() }
        activePortfolio.value = "p1"
        viewModel = createViewModel()
        assertEquals(50, (viewModel.uiState.value as TransactionHistoryUiState.Success).transactions.size)

        activePortfolio.value = "p2"

        // Les 50 lignes de p1 ne doivent plus être visibles sous p2 pendant le rechargement.
        assertTrue(
            "Expected Loading while p2 loads, got ${viewModel.uiState.value}",
            viewModel.uiState.value is TransactionHistoryUiState.Loading,
        )
        gate.complete(Result.success((101L..110L).map(::tx)))

        val state = viewModel.uiState.value as TransactionHistoryUiState.Success
        assertEquals((101L..110L).toList(), state.transactions.map { it.id })
        assertFalse("10 lignes < 50 : pas de page suivante", state.hasMore)
        coVerify(exactly = 1) { getTransactionsUseCase("p2", any(), 0, any()) }
    }

    @Test
    fun `pagination restarts from offset 0 for the new portfolio`() = runTest {
        coEvery { getTransactionsUseCase("p1", any(), 0, any()) } returns Result.success(firstPage)
        coEvery { getTransactionsUseCase("p1", any(), 50, any()) } returns
            Result.success((51L..100L).map(::tx))
        coEvery { getTransactionsUseCase("p2", any(), 0, any()) } returns
            Result.success((201L..250L).map(::tx))
        coEvery { getTransactionsUseCase("p2", any(), 50, any()) } returns
            Result.success((251L..260L).map(::tx))
        activePortfolio.value = "p1"
        viewModel = createViewModel()
        viewModel.loadMore() // p1 : offset 100 après cette page

        activePortfolio.value = "p2"
        viewModel.loadMore()

        // Sans remise à zéro de l'offset, cette page aurait été demandée à l'offset 150.
        coVerify(exactly = 1) { getTransactionsUseCase("p2", any(), 50, any()) }
        val state = viewModel.uiState.value as TransactionHistoryUiState.Success
        assertEquals(60, state.transactions.size)
        assertEquals(201L, state.transactions.first().id)
    }

    @Test
    fun `a response arriving after a portfolio switch never reaches the screen`() = runTest {
        val lateP1 = CompletableDeferred<Result<List<Transaction>>>()
        coEvery { getTransactionsUseCase("p1", any(), 0, any()) } coAnswers { lateP1.await() }
        coEvery { getTransactionsUseCase("p2", any(), 0, any()) } returns
            Result.success((201L..205L).map(::tx))
        activePortfolio.value = "p1"
        viewModel = createViewModel() // le chargement de p1 est suspendu

        activePortfolio.value = "p2"
        lateP1.complete(Result.success(firstPage)) // réponse tardive de p1

        val state = viewModel.uiState.value as TransactionHistoryUiState.Success
        assertEquals((201L..205L).toList(), state.transactions.map { it.id })
    }
}
