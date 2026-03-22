package com.tradingplatform.app.ui.screens.performance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.model.PerformanceMetrics
import com.tradingplatform.app.domain.usecase.auth.GetPortfolioIdUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPerformanceUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val getPortfolioIdUseCase: GetPortfolioIdUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<PerformanceUiState>(PerformanceUiState.Loading)
    val uiState: StateFlow<PerformanceUiState> = _uiState.asStateFlow()

    private var portfolioId: String = ""

    init {
        viewModelScope.launch {
            portfolioId = getPortfolioIdUseCase()
            fetchPerformance()
        }
    }

    fun refresh() {
        if (portfolioId.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = PerformanceUiState.Loading
            fetchPerformance()
        }
    }

    private suspend fun fetchPerformance() {
        getPerformanceUseCase(portfolioId)
            .onSuccess { metrics ->
                _uiState.value = PerformanceUiState.Success(metrics)
            }
            .onFailure { e ->
                _uiState.value = PerformanceUiState.Error(
                    e.localizedMessage ?: "Erreur lors du chargement des performances",
                )
            }
    }
}
