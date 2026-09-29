package com.tradingplatform.app.ui.screens.portfolio

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.model.Position
import com.tradingplatform.app.domain.model.PositionStatus
import com.tradingplatform.app.domain.model.WsUpdate
import com.tradingplatform.app.domain.usecase.portfolio.GetPositionWsUpdatesUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPositionsUseCase
import com.tradingplatform.app.domain.usecase.portfolio.ObserveActivePortfolioUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PositionsUiState {
    data object Loading : PositionsUiState
    /**
     * [isRefreshing] : un rafraîchissement est en cours — la liste reste affichée (pas de retour au
     * skeleton). [refreshError] : le dernier rafraîchissement a échoué ; la liste affichée est donc
     * périmée (avec son `syncedAt` réel) et l'écran l'annonce sans la masquer.
     */
    data class Success(
        val positions: List<Position>,
        val syncedAt: Long,
        val isRefreshing: Boolean = false,
        val refreshError: String? = null,
    ) : PositionsUiState
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
    private val observeActivePortfolioUseCase: ObserveActivePortfolioUseCase,
    private val getPositionWsUpdatesUseCase: GetPositionWsUpdatesUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<PositionsUiState>(PositionsUiState.Loading)
    val uiState: StateFlow<PositionsUiState> = _uiState.asStateFlow()

    private val _selectedFilter = MutableStateFlow(StatusFilter.OPEN)
    val selectedFilter: StateFlow<StatusFilter> = _selectedFilter.asStateFlow()

    /**
     * Test-only seam: moves [selectedFilter] without cancelling [loadJob], so a test can
     * reproduce the race window between an in-flight [loadPositions] response and a filter
     * change, independently of `Job` cancellation (see [loadPositions] kdoc).
     */
    @VisibleForTesting
    internal fun forceSelectedFilterForRaceTest(filter: StatusFilter) {
        _selectedFilter.value = filter
    }

    /** Portefeuille actif courant ; vide tant que le premier id n'est pas connu. */
    private var portfolioId: String = ""

    /** Tracks the in-flight load so a filter change/refresh can cancel a stale one. */
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            // collectLatest : un changement de portefeuille actif annule le chargement de l'ancien.
            observeActivePortfolioUseCase().collectLatest { id -> onActivePortfolio(id) }
        }
        collectPositionWsUpdates()
    }

    /**
     * Nouveau portefeuille actif (dont le premier) : l'état est remis à [PositionsUiState.Loading]
     * AVANT le rechargement, pour ne jamais laisser afficher les positions de l'ancien portefeuille.
     * Le filtre courant est conservé. Le chargement est un enfant du bloc `collectLatest` (annulé
     * avec lui) tout en restant tracé par [loadJob] pour que filtre/refresh puissent l'annuler.
     */
    private suspend fun onActivePortfolio(id: String) {
        loadJob?.cancel()
        portfolioId = id
        _uiState.value = PositionsUiState.Loading
        coroutineScope {
            loadJob = launch { loadPositions(_selectedFilter.value) }
        }
    }

    fun selectFilter(filter: StatusFilter) {
        loadJob?.cancel()
        _selectedFilter.value = filter
        // Portefeuille encore inconnu : le chargement partira à sa résolution avec ce filtre.
        if (portfolioId.isEmpty()) return
        loadJob = viewModelScope.launch { loadPositions(filter) }
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
     *
     * Multi-portefeuille : un [WsUpdate.PositionUpdate] dont `portfolioId` est non nul et différent du
     * portefeuille actif est ignoré (un `position_update` d'un autre portefeuille, même symbole).
     */
    private fun collectPositionWsUpdates() {
        viewModelScope.launch {
            getPositionWsUpdatesUseCase().collect { wsUpdate ->
                // Compte multi-portefeuilles : un position_update d'un autre portefeuille (même symbole)
                // ne doit pas modifier la liste affichée.
                if (wsUpdate.portfolioId != null && wsUpdate.portfolioId != portfolioId) return@collect
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
        if (portfolioId.isEmpty()) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch { loadPositions(_selectedFilter.value, keepCurrent = true) }
    }

    /**
     * Loads positions for [requestedFilter].
     *
     * [selectFilter]/[refresh] always cancel the previous [loadJob] before launching a new
     * one, which normally prevents an out-of-order response from overwriting a newer one.
     * The [_selectedFilter] check below is a defense-in-depth guard for the residual window
     * between a response arriving and cancellation taking effect — it drops the result if the
     * user has since moved on to a different filter, rather than trusting cancellation alone.
     *
     * Un second garde écarte de la même façon un résultat arrivé après un changement de portefeuille
     * actif : il ne doit jamais s'afficher sous le nouveau portefeuille.
     *
     * Internal (not private) so tests can drive this directly to exercise that guard without
     * depending on coroutine cancellation timing.
     */
    @VisibleForTesting
    internal suspend fun loadPositions(requestedFilter: StatusFilter, keepCurrent: Boolean = false) {
        val requestedPortfolioId = portfolioId
        // Rafraîchissement du MÊME filtre : la liste affichée reste à l'écran (indicateur du
        // pull-to-refresh) au lieu de retomber sur un skeleton. Changement de filtre / premier
        // chargement : la liste courante n'est pas celle demandée → skeleton.
        _uiState.update { current ->
            if (keepCurrent && current is PositionsUiState.Success) {
                current.copy(isRefreshing = true, refreshError = null)
            } else {
                PositionsUiState.Loading
            }
        }
        val status = when (requestedFilter) {
            StatusFilter.OPEN -> PositionStatus.OPEN
            StatusFilter.CLOSED -> PositionStatus.CLOSED
            StatusFilter.ALL -> PositionStatus.ALL
        }
        getPositionsUseCase(requestedPortfolioId, status)
            .onSuccess { positions ->
                if (_selectedFilter.value != requestedFilter || portfolioId != requestedPortfolioId) {
                    return@onSuccess
                }
                _uiState.update {
                    PositionsUiState.Success(
                        positions = positions,
                        syncedAt = System.currentTimeMillis(),
                    )
                }
            }
            .onFailure { e ->
                if (_selectedFilter.value != requestedFilter || portfolioId != requestedPortfolioId) {
                    return@onFailure
                }
                val message = e.localizedMessage ?: "Erreur"
                _uiState.update { current ->
                    // Valeur périmée conservée + erreur annoncée, plutôt que d'effacer la liste.
                    if (current is PositionsUiState.Success) {
                        current.copy(isRefreshing = false, refreshError = message)
                    } else {
                        PositionsUiState.Error(message)
                    }
                }
            }
    }
}
