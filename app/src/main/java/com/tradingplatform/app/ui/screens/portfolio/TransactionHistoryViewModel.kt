package com.tradingplatform.app.ui.screens.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.model.Transaction
import com.tradingplatform.app.domain.usecase.auth.GetPortfolioIdUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetTransactionsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface TransactionHistoryUiState {
    data object Loading : TransactionHistoryUiState
    data class Success(
        val transactions: List<Transaction>,
        val hasMore: Boolean,
        val isLoadingMore: Boolean = false,
    ) : TransactionHistoryUiState
    data class Error(val message: String) : TransactionHistoryUiState
}

@HiltViewModel
class TransactionHistoryViewModel @Inject constructor(
    private val getTransactionsUseCase: GetTransactionsUseCase,
    private val getPortfolioIdUseCase: GetPortfolioIdUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<TransactionHistoryUiState>(TransactionHistoryUiState.Loading)
    val uiState: StateFlow<TransactionHistoryUiState> = _uiState.asStateFlow()

    private var portfolioId: String = ""
    private var currentOffset = 0
    private val pageSize = 50
    private val allTransactions = mutableListOf<Transaction>()

    /** Tracks the single in-flight load (initial/refresh or "Charger plus") for guarding/cancellation. */
    private var loadJob: Job? = null

    init {
        loadJob = viewModelScope.launch {
            portfolioId = getPortfolioIdUseCase()
            loadTransactions()
        }
    }

    fun refresh() {
        loadJob?.cancel()
        currentOffset = 0
        allTransactions.clear()
        loadJob = viewModelScope.launch { loadTransactions() }
    }

    /** No-op while a load (initial, refresh, or a previous "Charger plus") is already in flight. */
    fun loadMore() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch { loadTransactions() }
    }

    private suspend fun loadTransactions() {
        if (currentOffset == 0) {
            _uiState.update { TransactionHistoryUiState.Loading }
        } else {
            _uiState.update { current ->
                if (current is TransactionHistoryUiState.Success) {
                    current.copy(isLoadingMore = true)
                } else {
                    current
                }
            }
        }
        getTransactionsUseCase(portfolioId, limit = pageSize, offset = currentOffset)
            .onSuccess { transactions ->
                allTransactions.addAll(transactions)
                currentOffset += transactions.size
                val deduped = allTransactions.distinctBy { it.id }
                allTransactions.clear()
                allTransactions.addAll(deduped)
                _uiState.update {
                    TransactionHistoryUiState.Success(
                        transactions = allTransactions.toList(),
                        hasMore = transactions.size == pageSize,
                        isLoadingMore = false,
                    )
                }
            }
            .onFailure { e ->
                _uiState.update {
                    TransactionHistoryUiState.Error(e.localizedMessage ?: "Erreur")
                }
            }
    }
}
