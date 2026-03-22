package com.tradingplatform.app.ui.screens.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.model.Position
import com.tradingplatform.app.domain.model.WsUpdate
import com.tradingplatform.app.domain.usecase.auth.GetPortfolioIdUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPositionWsUpdatesUseCase
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
    private val getPositionWsUpdatesUseCase: GetPositionWsUpdatesUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<PositionsUiState>(PositionsUiState.Loading)
    val uiState: StateFlow<PositionsUiState> = _uiState.asStateFlow()

    private var portfolioId: String = ""

    init {
        viewModelScope.launch {
            portfolioId = getPortfolioIdUseCase()
            loadPositions()
        }
        collectPositionWsUpdates()
    }

    /**
     * Collect real-time position updates from the private WebSocket.
     *
     * Each [WsUpdate.PositionUpdate] is merged into the current positions list
     * by matching on positionId first, then symbol as fallback.
     * Updates are ignored when the UI state is not [PositionsUiState.Success].
     */
    private fun collectPositionWsUpdates() {
        viewModelScope.launch {
            getPositionWsUpdatesUseCase().collect { wsUpdate ->
                _uiState.update { current ->
                    if (current !is PositionsUiState.Success) return@update current
                    val updatedPositions = current.positions.map { position ->
                        if (matchesPosition(position, wsUpdate)) {
                            position.copy(
                                currentPrice = wsUpdate.currentPrice?.toBigDecimal()
                                    ?: position.currentPrice,
                                unrealizedPnl = wsUpdate.unrealizedPnl?.toBigDecimal()
                                    ?: position.unrealizedPnl,
                            )
                        } else {
                            position
                        }
                    }
                    current.copy(positions = updatedPositions)
                }
            }
        }
    }

    /**
     * Match a [WsUpdate.PositionUpdate] to a [Position] — prefer positionId,
     * fall back to symbol.
     */
    private fun matchesPosition(position: Position, update: WsUpdate.PositionUpdate): Boolean {
        val byId = update.positionId?.let { it == position.id.toString() } ?: false
        if (byId) return true
        return update.symbol?.let { it == position.symbol } ?: false
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
