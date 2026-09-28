package com.tradingplatform.app.ui.screens.portfolio

import com.tradingplatform.app.domain.model.Transaction
import com.tradingplatform.app.domain.usecase.auth.GetPortfolioIdUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetTransactionsUseCase
import com.tradingplatform.app.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    private val getPortfolioIdUseCase = mockk<GetPortfolioIdUseCase>()

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
        coEvery { getPortfolioIdUseCase() } returns "1"
        coEvery { getTransactionsUseCase(any(), any(), 0, any()) } returns Result.success(firstPage)
    }

    private fun createViewModel(): TransactionHistoryViewModel = TransactionHistoryViewModel(
        getTransactionsUseCase = getTransactionsUseCase,
        getPortfolioIdUseCase = getPortfolioIdUseCase,
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
}
