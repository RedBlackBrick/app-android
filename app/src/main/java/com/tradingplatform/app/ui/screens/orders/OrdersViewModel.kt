package com.tradingplatform.app.ui.screens.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.model.Order
import com.tradingplatform.app.domain.usecase.auth.GetPortfolioIdUseCase
import com.tradingplatform.app.domain.usecase.orders.GetActiveOrdersUseCase
import com.tradingplatform.app.domain.usecase.orders.GetOrderHistoryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UiState for [OrdersScreen].
 *
 * The screen has two tabs (active / history) backed by independent fetches.
 * Each tab carries its own [TabState] so a failure on one does not blank out
 * the other. ``Loading`` is the default; once the fetch completes the state
 * transitions to either ``Success`` (possibly with an empty list) or ``Error``.
 */
sealed interface OrdersTabState {
    data object Loading : OrdersTabState
    data class Success(
        val orders: List<Order>,
        val hasMore: Boolean = false,
        val isLoadingMore: Boolean = false,
    ) : OrdersTabState
    data class Error(val message: String) : OrdersTabState
}

enum class OrdersTab { ACTIVE, HISTORY }

data class OrdersUiState(
    val selectedTab: OrdersTab = OrdersTab.ACTIVE,
    val active: OrdersTabState = OrdersTabState.Loading,
    val history: OrdersTabState = OrdersTabState.Loading,
    val portfolioId: String = "",
)

@HiltViewModel
class OrdersViewModel @Inject constructor(
    private val getPortfolioIdUseCase: GetPortfolioIdUseCase,
    private val getActiveOrdersUseCase: GetActiveOrdersUseCase,
    private val getOrderHistoryUseCase: GetOrderHistoryUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OrdersUiState())
    val uiState: StateFlow<OrdersUiState> = _uiState.asStateFlow()

    private val historyPageSize = 50
    private var historyOffset = 0
    private val allHistoryOrders = mutableListOf<Order>()

    /** Tracks the in-flight history fetch (first page or "Charger plus") for cancellation/guarding. */
    private var historyLoadJob: Job? = null

    init {
        viewModelScope.launch {
            val portfolioId = getPortfolioIdUseCase()
            _uiState.update { it.copy(portfolioId = portfolioId) }
            if (portfolioId.isBlank()) {
                // Mirrors refresh()'s guard — an inconsistent local state (no portfolio
                // resolved yet) must not trigger a network call with an empty id.
                _uiState.update {
                    it.copy(
                        active = OrdersTabState.Error(PORTFOLIO_NOT_FOUND_MESSAGE),
                        history = OrdersTabState.Error(PORTFOLIO_NOT_FOUND_MESSAGE),
                    )
                }
                return@launch
            }
            // Pre-fetch both tabs so the user sees data immediately when switching.
            launch { fetchActive(portfolioId) }
            historyLoadJob = launch { fetchHistoryFirstPage(portfolioId) }
        }
    }

    fun selectTab(tab: OrdersTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun refresh() {
        val portfolioId = _uiState.value.portfolioId
        if (portfolioId.isBlank()) return
        historyLoadJob?.cancel()
        viewModelScope.launch {
            launch { fetchActive(portfolioId) }
            historyLoadJob = launch { fetchHistoryFirstPage(portfolioId) }
        }
    }

    /** No-op while a history fetch (first page or a previous "Charger plus") is already in flight. */
    fun loadMoreHistory() {
        val portfolioId = _uiState.value.portfolioId
        if (portfolioId.isBlank()) return
        if (historyLoadJob?.isActive == true) return
        historyLoadJob = viewModelScope.launch { fetchHistoryNextPage(portfolioId) }
    }

    private suspend fun fetchActive(portfolioId: String) {
        _uiState.update { it.copy(active = OrdersTabState.Loading) }
        getActiveOrdersUseCase(portfolioId)
            .onSuccess { orders ->
                _uiState.update { it.copy(active = OrdersTabState.Success(orders)) }
            }
            .onFailure { e ->
                _uiState.update {
                    it.copy(active = OrdersTabState.Error(e.localizedMessage ?: "Erreur"))
                }
            }
    }

    private suspend fun fetchHistoryFirstPage(portfolioId: String) {
        historyOffset = 0
        allHistoryOrders.clear()
        _uiState.update { it.copy(history = OrdersTabState.Loading) }
        loadHistoryPage(portfolioId)
    }

    private suspend fun fetchHistoryNextPage(portfolioId: String) {
        _uiState.update { current ->
            val history = current.history
            if (history is OrdersTabState.Success) {
                current.copy(history = history.copy(isLoadingMore = true))
            } else {
                current
            }
        }
        loadHistoryPage(portfolioId)
    }

    private suspend fun loadHistoryPage(portfolioId: String) {
        getOrderHistoryUseCase(portfolioId, limit = historyPageSize, offset = historyOffset)
            .onSuccess { page ->
                allHistoryOrders.addAll(page.items)
                historyOffset += page.items.size
                val deduped = allHistoryOrders.distinctBy { it.id }
                allHistoryOrders.clear()
                allHistoryOrders.addAll(deduped)
                _uiState.update {
                    it.copy(
                        history = OrdersTabState.Success(
                            orders = allHistoryOrders.toList(),
                            hasMore = allHistoryOrders.size < page.total,
                            isLoadingMore = false,
                        ),
                    )
                }
            }
            .onFailure { e ->
                _uiState.update {
                    it.copy(history = OrdersTabState.Error(e.localizedMessage ?: "Erreur"))
                }
            }
    }

    private companion object {
        const val PORTFOLIO_NOT_FOUND_MESSAGE = "Portfolio introuvable"
    }
}
