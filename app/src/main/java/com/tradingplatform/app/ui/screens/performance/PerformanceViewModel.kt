package com.tradingplatform.app.ui.screens.performance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.model.PerformanceMetrics
import com.tradingplatform.app.domain.usecase.portfolio.GetPerformanceUseCase
import com.tradingplatform.app.domain.usecase.portfolio.ObserveActivePortfolioUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── UiState ─────────────────────────────────────────────────────────────────────

sealed interface PerformanceUiState {
    data object Loading : PerformanceUiState
    data class Success(val metrics: PerformanceMetrics) : PerformanceUiState
    data class Error(val message: String) : PerformanceUiState
}

// ── ViewModel ───────────────────────────────────────────────────────────────────

@HiltViewModel
class PerformanceViewModel @Inject constructor(
    private val getPerformanceUseCase: GetPerformanceUseCase,
    private val observeActivePortfolioUseCase: ObserveActivePortfolioUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<PerformanceUiState>(PerformanceUiState.Loading)
    val uiState: StateFlow<PerformanceUiState> = _uiState.asStateFlow()

    /** Portefeuille actif courant ; vide tant que le premier id n'est pas connu. */
    private var portfolioId: String = ""

    /** Rafraîchissement manuel en cours — annulé au changement de portefeuille ou par un nouveau. */
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            // collectLatest : un changement de portefeuille actif annule le chargement de l'ancien.
            observeActivePortfolioUseCase().collectLatest { id ->
                // Remise à zéro AVANT le rechargement : jamais les métriques de l'ancien portefeuille.
                refreshJob?.cancel()
                portfolioId = id
                _uiState.value = PerformanceUiState.Loading
                fetchPerformance()
            }
        }
    }

    fun refresh() {
        if (portfolioId.isEmpty()) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _uiState.value = PerformanceUiState.Loading
            fetchPerformance()
        }
    }

    private suspend fun fetchPerformance() {
        val requestedPortfolioId = portfolioId
        getPerformanceUseCase(requestedPortfolioId)
            .onSuccess { metrics ->
                // Réponse d'un portefeuille qui n'est plus l'actif : jamais affichée.
                if (portfolioId != requestedPortfolioId) return@onSuccess
                _uiState.value = PerformanceUiState.Success(metrics)
            }
            .onFailure { e ->
                if (portfolioId != requestedPortfolioId) return@onFailure
                _uiState.value = PerformanceUiState.Error(
                    e.localizedMessage ?: "Erreur lors du chargement des performances",
                )
            }
    }
}
