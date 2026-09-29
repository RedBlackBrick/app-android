package com.tradingplatform.app.ui.screens.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.model.Transaction
import com.tradingplatform.app.domain.usecase.portfolio.GetTransactionsUseCase
import com.tradingplatform.app.domain.usecase.portfolio.ObserveActivePortfolioUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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
    private val observeActivePortfolioUseCase: ObserveActivePortfolioUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<TransactionHistoryUiState>(TransactionHistoryUiState.Loading)
    val uiState: StateFlow<TransactionHistoryUiState> = _uiState.asStateFlow()

    /** Portefeuille actif courant ; vide tant que le premier id n'est pas connu. */
    private var portfolioId: String = ""
    private var currentOffset = 0
    private val pageSize = 50
    private val allTransactions = mutableListOf<Transaction>()

    /** Tracks the single in-flight load (initial/refresh or "Charger plus") for guarding/cancellation. */
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            // collectLatest : un changement de portefeuille actif annule le chargement de l'ancien.
            observeActivePortfolioUseCase().collectLatest { id -> onActivePortfolio(id) }
        }
    }

    /**
     * Nouveau portefeuille actif (dont le premier) : liste, offset et état sont remis à zéro AVANT
     * le rechargement de la première page, pour ne jamais laisser afficher l'historique de l'ancien
     * portefeuille. Le chargement est un enfant du bloc `collectLatest` (annulé avec lui) tout en
     * restant tracé par [loadJob], ce qui garde le garde-fou de réentrance de [loadMore].
     */
    private suspend fun onActivePortfolio(id: String) {
        loadJob?.cancel()
        portfolioId = id
        currentOffset = 0
        allTransactions.clear()
        _uiState.value = TransactionHistoryUiState.Loading
        coroutineScope {
            loadJob = launch { loadTransactions() }
        }
    }

    fun refresh() {
        if (portfolioId.isEmpty()) return
        loadJob?.cancel()
        currentOffset = 0
        allTransactions.clear()
        loadJob = viewModelScope.launch { loadTransactions() }
    }

    /** No-op while a load (initial, refresh, or a previous "Charger plus") is already in flight. */
    fun loadMore() {
        if (portfolioId.isEmpty()) return
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch { loadTransactions() }
    }

    private suspend fun loadTransactions() {
        val requestedPortfolioId = portfolioId
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
        getTransactionsUseCase(requestedPortfolioId, limit = pageSize, offset = currentOffset)
            .onSuccess { transactions ->
                // Réponse d'un portefeuille qui n'est plus l'actif : jamais affichée.
                if (portfolioId != requestedPortfolioId) return@onSuccess
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
                if (portfolioId != requestedPortfolioId) return@onFailure
                _uiState.update {
                    TransactionHistoryUiState.Error(e.localizedMessage ?: "Erreur")
                }
            }
    }
}
