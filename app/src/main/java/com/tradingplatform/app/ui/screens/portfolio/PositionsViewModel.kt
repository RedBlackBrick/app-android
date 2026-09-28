package com.tradingplatform.app.ui.screens.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.model.Position
import com.tradingplatform.app.domain.model.PositionStatus
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

enum class StatusFilter(val label: String) {
    OPEN("Ouvertes"),
    CLOSED("Fermées"),
    ALL("Toutes"),
}

@HiltViewModel
class PositionsViewModel @Inject constructor(
    private val getPositionsUseCase: GetPositionsUseCase,
    private val getPortfolioIdUseCase: GetPortfolioIdUseCase,
    private val getPositionWsUpdatesUseCase: GetPositionWsUpdatesUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<PositionsUiState>(PositionsUiState.Loading)
    val uiState: StateFlow<PositionsUiState> = _uiState.asStateFlow()

    private val _selectedFilter = MutableStateFlow(StatusFilter.OPEN)
    val selectedFilter: StateFlow<StatusFilter> = _selectedFilter.asStateFlow()

    private var portfolioId: String = ""

    init {
        viewModelScope.launch {
            portfolioId = getPortfolioIdUseCase()
            loadPositions()
        }
        collectPositionWsUpdates()
    }

    fun selectFilter(filter: StatusFilter) {
        _selectedFilter.value = filter
        viewModelScope.launch { loadPositions() }
    }

    /**
     * Collect real-time position updates from the private WebSocket.
     *
     * Each [WsUpdate.PositionUpdate] is merged into the current positions list
     * via [matchesPosition]. The backend does not currently send `position_id`,
     * so matching falls back to symbol scoped to the OPEN status — this avoids
     * a symbol match hitting a stale CLOSED row when the same symbol was
     * traded, closed and reopened (visible under the ALL filter).
     *
     * `isActive == false` means the fill fully closed the position: it is
     * dropped from the list under the OPEN filter (it no longer belongs there)
     * and marked CLOSED otherwise (ALL/CLOSED filters keep it visible).
     *
     * Updates are ignored when the UI state is not [PositionsUiState.Success].
     */
    private fun collectPositionWsUpdates() {
        viewModelScope.launch {
            getPositionWsUpdatesUseCase().collect { wsUpdate ->
                _uiState.update { current ->
                    if (current !is PositionsUiState.Success) return@update current
                    val filter = _selectedFilter.value
                    val updatedPositions = current.positions.mapNotNull { position ->
                        if (!matchesPosition(position, wsUpdate)) return@mapNotNull position
                        if (!wsUpdate.isActive) {
                            return@mapNotNull if (filter == StatusFilter.OPEN) {
                                null
                            } else {
                                position.copy(status = PositionStatus.CLOSED)
                            }
                        }
                        position.copy(
                            currentPrice = wsUpdate.lastPrice?.toBigDecimal()
                                ?: position.currentPrice,
                            unrealizedPnl = wsUpdate.unrealizedPnl?.toBigDecimal()
                                ?: position.unrealizedPnl,
                            quantity = wsUpdate.quantity?.toBigDecimal()
                                ?: position.quantity,
                        )
                    }
                    current.copy(positions = updatedPositions)
                }
            }
        }
    }

    /**
     * Match a [WsUpdate.PositionUpdate] to a [Position].
     *
     * When the backend sends an explicit `position_id`, match it exactly —
     * this is unambiguous regardless of status. Otherwise (current backend
     * behavior), fall back to symbol matching scoped to OPEN positions only:
     * a symbol alone cannot disambiguate between a closed and a reopened
     * position of the same ticker.
     */
    private fun matchesPosition(position: Position, update: WsUpdate.PositionUpdate): Boolean {
        val positionId = update.positionId
        if (positionId != null) {
            return positionId == position.id.toString()
        }
        return position.status == PositionStatus.OPEN &&
            update.symbol != null &&
            update.symbol == position.symbol
    }

    fun refresh() {
        viewModelScope.launch { loadPositions() }
    }

    private suspend fun loadPositions() {
        _uiState.update { PositionsUiState.Loading }
        val status = when (_selectedFilter.value) {
            StatusFilter.OPEN -> PositionStatus.OPEN
            StatusFilter.CLOSED -> PositionStatus.CLOSED
            StatusFilter.ALL -> PositionStatus.ALL
        }
        getPositionsUseCase(portfolioId, status)
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
