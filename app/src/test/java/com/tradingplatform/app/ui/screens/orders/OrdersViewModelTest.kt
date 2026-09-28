package com.tradingplatform.app.ui.screens.orders

import com.tradingplatform.app.domain.model.Order
import com.tradingplatform.app.domain.model.OrderSide
import com.tradingplatform.app.domain.model.OrderStatus
import com.tradingplatform.app.domain.model.OrderType
import com.tradingplatform.app.domain.model.Page
import com.tradingplatform.app.domain.usecase.auth.GetPortfolioIdUseCase
import com.tradingplatform.app.domain.usecase.orders.GetActiveOrdersUseCase
import com.tradingplatform.app.domain.usecase.orders.GetOrderHistoryUseCase
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

/**
 * Covers findings C-orders-corr-2 (audit fetches with a possibly empty portfolioId while
 * `refresh` guards it — init should guard the same way) and C-orders-corr-3 (history capped
 * at 50, `count` discarded, no pagination) from audit/candidates-C-ui-vpn.md.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrdersViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val getPortfolioIdUseCase = mockk<GetPortfolioIdUseCase>()
    private val getActiveOrdersUseCase = mockk<GetActiveOrdersUseCase>()
    private val getOrderHistoryUseCase = mockk<GetOrderHistoryUseCase>()

    private lateinit var viewModel: OrdersViewModel

    private fun order(id: Long): Order = Order(
        id = id,
        symbol = "TSLA",
        side = OrderSide.BUY,
        quantity = BigDecimal("1"),
        orderType = OrderType.MARKET,
        status = OrderStatus.FILLED,
        filledQuantity = BigDecimal("1"),
        averageFillPrice = BigDecimal("250.00"),
        limitPrice = null,
        stopPrice = null,
        portfolioId = "1",
        brokerOrderId = null,
        createdAt = null,
        updatedAt = null,
    )

    private fun createViewModel(): OrdersViewModel = OrdersViewModel(
        getPortfolioIdUseCase = getPortfolioIdUseCase,
        getActiveOrdersUseCase = getActiveOrdersUseCase,
        getOrderHistoryUseCase = getOrderHistoryUseCase,
    )

    // ── Blank portfolioId guard (C-orders-corr-2) ────────────────────────────────

    @Test
    fun `blank portfolioId sets Error on both tabs without calling the API`() = runTest {
        coEvery { getPortfolioIdUseCase() } returns ""

        viewModel = createViewModel()

        val state = viewModel.uiState.value
        assertTrue(state.active is OrdersTabState.Error)
        assertTrue(state.history is OrdersTabState.Error)
        assertEquals(
            "Portfolio introuvable",
            (state.active as OrdersTabState.Error).message,
        )
        assertEquals(
            "Portfolio introuvable",
            (state.history as OrdersTabState.Error).message,
        )
        coVerify(exactly = 0) { getActiveOrdersUseCase(any()) }
        coVerify(exactly = 0) { getOrderHistoryUseCase(any(), any(), any()) }
    }

    @Test
    fun `refresh is a no-op when portfolioId never resolved`() = runTest {
        coEvery { getPortfolioIdUseCase() } returns ""
        viewModel = createViewModel()

        viewModel.refresh()

        coVerify(exactly = 0) { getActiveOrdersUseCase(any()) }
        coVerify(exactly = 0) { getOrderHistoryUseCase(any(), any(), any()) }
    }

    // ── History pagination (C-orders-corr-3) ─────────────────────────────────────

    @Test
    fun `history hasMore is derived from the backend count, not page fullness`() = runTest {
        coEvery { getPortfolioIdUseCase() } returns "1"
        coEvery { getActiveOrdersUseCase(any()) } returns Result.success(emptyList())
        coEvery { getOrderHistoryUseCase(any(), any(), 0) } returns
            Result.success(Page(items = listOf(order(1), order(2)), total = 10))

        viewModel = createViewModel()

        val history = viewModel.uiState.value.history as OrdersTabState.Success
        assertEquals(2, history.orders.size)
        assertTrue("2 of 10 total loaded — hasMore must be true", history.hasMore)
    }

    @Test
    fun `loadMoreHistory appends the next page and clears hasMore once exhausted`() = runTest {
        coEvery { getPortfolioIdUseCase() } returns "1"
        coEvery { getActiveOrdersUseCase(any()) } returns Result.success(emptyList())
        coEvery { getOrderHistoryUseCase(any(), any(), 0) } returns
            Result.success(Page(items = listOf(order(1), order(2)), total = 3))
        coEvery { getOrderHistoryUseCase(any(), any(), 2) } returns
            Result.success(Page(items = listOf(order(3)), total = 3))

        viewModel = createViewModel()
        viewModel.loadMoreHistory()

        val history = viewModel.uiState.value.history as OrdersTabState.Success
        assertEquals(listOf(1L, 2L, 3L), history.orders.map { it.id })
        assertFalse("All 3 of 3 loaded — no more pages", history.hasMore)
        assertFalse(history.isLoadingMore)
        coVerify(exactly = 1) { getOrderHistoryUseCase(any(), any(), 2) }
    }

    @Test
    fun `double loadMoreHistory before the first completes triggers only one use-case call`() = runTest {
        coEvery { getPortfolioIdUseCase() } returns "1"
        coEvery { getActiveOrdersUseCase(any()) } returns Result.success(emptyList())
        coEvery { getOrderHistoryUseCase(any(), any(), 0) } returns
            Result.success(Page(items = listOf(order(1), order(2)), total = 10))

        viewModel = createViewModel()

        val deferred = CompletableDeferred<Result<Page<Order>>>()
        coEvery { getOrderHistoryUseCase(any(), any(), 2) } coAnswers { deferred.await() }

        viewModel.loadMoreHistory()
        viewModel.loadMoreHistory() // must no-op: a history load is already active

        deferred.complete(Result.success(Page(items = listOf(order(3)), total = 10)))

        coVerify(exactly = 1) { getOrderHistoryUseCase(any(), any(), 2) }
    }
}
