package com.tradingplatform.app.ui.screens.portfolio

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.model.Position
import com.tradingplatform.app.domain.model.Transaction
import com.tradingplatform.app.domain.usecase.portfolio.GetPositionUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetTransactionsUseCase
import com.tradingplatform.app.domain.usecase.portfolio.ObserveActivePortfolioUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    private val observeActivePortfolioUseCase: ObserveActivePortfolioUseCase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val positionId: Int = checkNotNull(savedStateHandle["positionId"])

    private val _uiState = MutableStateFlow<PositionDetailUiState>(PositionDetailUiState.Loading)
    val uiState: StateFlow<PositionDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            loadDetail(forceRefresh = false)
        }
    }

    /** Retry / refresh : contourne toujours le cache Room de la position. */
    fun refresh() {
        viewModelScope.launch { loadDetail(forceRefresh = true) }
    }

    private suspend fun loadDetail(forceRefresh: Boolean) {
        _uiState.update { PositionDetailUiState.Loading }
        // Position identifiée par la route : pas de collecte continue du portefeuille actif, on lit
        // sa valeur courante (suspend tant qu'elle est inconnue) à chaque chargement / rafraîchissement.
        val portfolioId = observeActivePortfolioUseCase().first()

        // Une seule position par ID — cache Room servi s'il est frais (< CacheTtl.POSITIONS_MS)
        val positionResult = getPositionUseCase(portfolioId, positionId, forceRefresh)

        val cached = positionResult.getOrNull()
        if (cached == null) {
            val errorMsg = positionResult.exceptionOrNull()?.localizedMessage
                ?: "Position introuvable"
            _uiState.update { PositionDetailUiState.Error(errorMsg) }
            return
        }
        val position = cached.value

        // Transactions filtrées par symbole — un échec n'empêche pas d'afficher la position.
        // syncedAt = horodatage réel de la position (cache ou réseau), pas l'instant d'affichage.
        val transactions = getTransactionsUseCase(
            portfolioId = portfolioId,
            limit = 50,
            symbol = position.symbol,
        ).getOrDefault(emptyList())

        _uiState.update {
            PositionDetailUiState.Success(
                position = position,
                transactions = transactions,
                syncedAt = cached.syncedAt,
            )
        }
    }
}
