package com.tradingplatform.app.ui.screens.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.model.Position
import com.tradingplatform.app.domain.usecase.auth.GetPortfolioIdUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPositionsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PositionsUiState {
    data object Loading : PositionsUiState
    data class Success(val positions: List<Position>, val syncedAt: Long) : PositionsUiState
    data class Error(val message: String) : PositionsUiState
}

@HiltViewModel
class PositionsViewModel @Inject constructor(
    private val getPositionsUseCase: GetPositionsUseCase,
    private val getPortfolioIdUseCase: GetPortfolioIdUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<PositionsUiState>(PositionsUiState.Loading)
    val uiState: StateFlow<PositionsUiState> = _uiState.asStateFlow()

    private var portfolioId: Int = 0

    init {
        viewModelScope.launch {
            portfolioId = getPortfolioIdUseCase()
            loadPositions()
        }
    }

    fun refresh() {
        viewModelScope.launch { loadPositions() }
    }

    private suspend fun loadPositions() {
        _uiState.update { PositionsUiState.Loading }
        getPositionsUseCase(portfolioId)
            .onSuccess { positions ->
                _uiState.update {
                    PositionsUiState.Success(
                        positions = positions,
                        syncedAt = System.currentTimeMillis(),
                    )
                }
            }
            .onFailure { e ->
                _uiState.update {
                    PositionsUiState.Error(e.localizedMessage ?: "Erreur")
                }
            }
    }
}
