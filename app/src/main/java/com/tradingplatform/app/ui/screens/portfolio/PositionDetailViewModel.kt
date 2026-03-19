package com.tradingplatform.app.ui.screens.portfolio

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.model.Position
import com.tradingplatform.app.domain.model.Transaction
import com.tradingplatform.app.domain.usecase.auth.GetPortfolioIdUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPositionUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetTransactionsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PositionDetailUiState {
    data object Loading : PositionDetailUiState
    data class Success(
        val position: Position,
        val transactions: List<Transaction>,
        val syncedAt: Long,
    ) : PositionDetailUiState
    data class Error(val message: String) : PositionDetailUiState
}

@HiltViewModel
class PositionDetailViewModel @Inject constructor(
    private val getPositionUseCase: GetPositionUseCase,
    private val getTransactionsUseCase: GetTransactionsUseCase,
    private val getPortfolioIdUseCase: GetPortfolioIdUseCase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val positionId: Int = checkNotNull(savedStateHandle["positionId"])

    private val _uiState = MutableStateFlow<PositionDetailUiState>(PositionDetailUiState.Loading)
    val uiState: StateFlow<PositionDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            loadDetail()
        }
    }

    fun refresh() {
        viewModelScope.launch { loadDetail() }
    }

    private suspend fun loadDetail() {
        _uiState.update { PositionDetailUiState.Loading }
        val portfolioId = getPortfolioIdUseCase()

        // Fetch only the single position by ID — no longer loads all positions
        val positionFetchedAt = System.currentTimeMillis()
        val positionResult = getPositionUseCase(portfolioId, positionId)

        val position = positionResult.getOrNull()
        if (position == null) {
            val errorMsg = positionResult.exceptionOrNull()?.localizedMessage
                ?: "Position introuvable"
            _uiState.update { PositionDetailUiState.Error(errorMsg) }
            return
        }

        // Fetch transactions filtered by symbol
        getTransactionsUseCase(
            portfolioId = portfolioId,
            limit = 50,
            symbol = position.symbol,
        )
            .onSuccess { transactions ->
                _uiState.update {
                    PositionDetailUiState.Success(
                        position = position,
                        transactions = transactions,
                        syncedAt = System.currentTimeMillis(),
                    )
                }
            }
            .onFailure {
                // Still show position even if transactions fail — use positionFetchedAt
                // so syncedAt reflects when the position data was actually retrieved
                _uiState.update {
                    PositionDetailUiState.Success(
                        position = position,
                        transactions = emptyList(),
                        syncedAt = positionFetchedAt,
                    )
                }
            }
    }
}
