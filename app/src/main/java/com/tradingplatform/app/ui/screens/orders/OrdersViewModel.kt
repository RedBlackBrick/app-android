package com.tradingplatform.app.ui.screens.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.model.Order
import com.tradingplatform.app.domain.usecase.auth.GetPortfolioIdUseCase
import com.tradingplatform.app.domain.usecase.orders.GetActiveOrdersUseCase
import com.tradingplatform.app.domain.usecase.orders.GetOrderHistoryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
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
    data class Success(val orders: List<Order>) : OrdersTabState
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

    init {
        viewModelScope.launch {
            val portfolioId = getPortfolioIdUseCase()
            _uiState.update { it.copy(portfolioId = portfolioId) }
            // Pre-fetch both tabs so the user sees data immediately when switching.
            launch { fetchActive(portfolioId) }
            launch { fetchHistory(portfolioId) }
        }
    }

    fun selectTab(tab: OrdersTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun refresh() {
        val portfolioId = _uiState.value.portfolioId
        if (portfolioId.isEmpty()) return
        viewModelScope.launch {
            launch { fetchActive(portfolioId) }
            launch { fetchHistory(portfolioId) }
        }
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

    private suspend fun fetchHistory(portfolioId: String) {
        _uiState.update { it.copy(history = OrdersTabState.Loading) }
        getOrderHistoryUseCase(portfolioId)
            .onSuccess { orders ->
                _uiState.update { it.copy(history = OrdersTabState.Success(orders)) }
            }
            .onFailure { e ->
                _uiState.update {
                    it.copy(history = OrdersTabState.Error(e.localizedMessage ?: "Erreur"))
                }
            }
    }
}
