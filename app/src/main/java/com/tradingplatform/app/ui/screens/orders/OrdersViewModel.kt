package com.tradingplatform.app.ui.screens.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.exception.HttpStatusException
import com.tradingplatform.app.domain.model.Order
import com.tradingplatform.app.domain.model.WriteOutcome
import com.tradingplatform.app.domain.usecase.orders.CancelOrderUseCase
import com.tradingplatform.app.domain.usecase.orders.GetActiveOrdersUseCase
import com.tradingplatform.app.domain.usecase.orders.GetOrderHistoryUseCase
import com.tradingplatform.app.domain.usecase.portfolio.ObserveActivePortfolioUseCase
import com.tradingplatform.app.domain.usecase.write.EvaluateWriteGateUseCase
import com.tradingplatform.app.domain.usecase.write.WriteGate
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

/**
 * UiState for [OrdersScreen].
 *
 * The screen has two tabs (active / history) backed by independent fetches.
 * Each tab carries its own [TabState] so a failure on one does not blank out
 * the other. ``Loading`` is the default; once the fetch completes the state
 * transitions to either ``Success`` (possibly with an empty list) or ``Error``.
 */
sealed interface OrdersTabState {
    data object Loading : OrdersTabState
    data class Success(
        val orders: List<Order>,
        val hasMore: Boolean = false,
        val isLoadingMore: Boolean = false,
    ) : OrdersTabState
    data class Error(val message: String) : OrdersTabState
}

enum class OrdersTab { ACTIVE, HISTORY }

data class OrdersUiState(
    val selectedTab: OrdersTab = OrdersTab.ACTIVE,
    val active: OrdersTabState = OrdersTabState.Loading,
    val history: OrdersTabState = OrdersTabState.Loading,
    val portfolioId: String = "",
    /**
     * Instant (epoch ms) de la dernière lecture réussie des ordres actifs ; `null` = jamais lue
     * pour ce portefeuille. C'est le `dataSyncedAt` de la garde d'écriture.
     */
    val activeSyncedAt: Long? = null,
    /** Ordre dont l'annulation attend la confirmation (feuille ouverte) ; `null` sinon. */
    val pendingCancel: Order? = null,
    /**
     * Ordre dont l'annulation est en vol (requête PUIS relecture) : tant qu'il est non nul, aucune
     * autre annulation ne peut être lancée.
     */
    val cancelInFlightId: Long? = null,
    /**
     * Ordres dont l'annulation a été demandée et que le serveur n'a pas encore « résolus » (une
     * relecture réussie les vide). Leur ligne affiche « Annulation demandée », sans bouton.
     */
    val cancelRequestedIds: Set<Long> = emptySet(),
    /** Message éphémère (snackbar), consommé par l'écran via [OrdersViewModel.onMessageShown]. */
    val message: String? = null,
)

@HiltViewModel
class OrdersViewModel @Inject constructor(
    private val observeActivePortfolioUseCase: ObserveActivePortfolioUseCase,
    private val getActiveOrdersUseCase: GetActiveOrdersUseCase,
    private val getOrderHistoryUseCase: GetOrderHistoryUseCase,
    private val cancelOrderUseCase: CancelOrderUseCase,
    private val evaluateWriteGateUseCase: EvaluateWriteGateUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OrdersUiState())
    val uiState: StateFlow<OrdersUiState> = _uiState.asStateFlow()

    private val historyPageSize = 50
    private var historyOffset = 0
    private val allHistoryOrders = mutableListOf<Order>()

    /** Tracks the in-flight history fetch (first page or "Charger plus") for cancellation/guarding. */
    private var historyLoadJob: Job? = null

    init {
        viewModelScope.launch {
            // collectLatest : un changement de portefeuille actif annule le chargement de l'ancien.
            observeActivePortfolioUseCase().collectLatest { portfolioId -> onActivePortfolio(portfolioId) }
        }
    }

    /**
     * Nouveau portefeuille actif (dont le premier) : les deux onglets et la pagination de
     * l'historique sont remis à zéro AVANT le rechargement, pour ne jamais laisser afficher les
     * ordres de l'ancien portefeuille. L'onglet sélectionné est conservé. Toute annulation en
     * attente de confirmation ou marquée « demandée » concernait l'ancien portefeuille : elle est
     * oubliée (une requête déjà partie, elle, va à son terme — voir [cancelInFlightId]).
     */
    private suspend fun onActivePortfolio(portfolioId: String) {
        historyLoadJob?.cancel()
        historyOffset = 0
        allHistoryOrders.clear()
        _uiState.update {
            it.copy(
                active = OrdersTabState.Loading,
                history = OrdersTabState.Loading,
                portfolioId = portfolioId,
                activeSyncedAt = null,
                pendingCancel = null,
                cancelRequestedIds = emptySet(),
            )
        }
        if (portfolioId.isBlank()) {
            // Mirrors refresh()'s guard — an inconsistent local state (no portfolio
            // resolved yet) must not trigger a network call with an empty id.
            _uiState.update {
                it.copy(
                    active = OrdersTabState.Error(PORTFOLIO_NOT_FOUND_MESSAGE),
                    history = OrdersTabState.Error(PORTFOLIO_NOT_FOUND_MESSAGE),
                )
            }
            return
        }
        // Pre-fetch both tabs so the user sees data immediately when switching. Les enfants sont
        // annulés avec ce bloc `collectLatest` au prochain changement de portefeuille.
        coroutineScope {
            launch { loadActive(portfolioId) }
            historyLoadJob = launch { fetchHistoryFirstPage(portfolioId) }
        }
    }

    fun selectTab(tab: OrdersTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun refresh() {
        val portfolioId = _uiState.value.portfolioId
        if (portfolioId.isBlank()) return
        historyLoadJob?.cancel()
        viewModelScope.launch {
            launch { loadActive(portfolioId) }
            historyLoadJob = launch { fetchHistoryFirstPage(portfolioId) }
        }
    }

    /** No-op while a history fetch (first page or a previous "Charger plus") is already in flight. */
    fun loadMoreHistory() {
        val portfolioId = _uiState.value.portfolioId
        if (portfolioId.isBlank()) return
        if (historyLoadJob?.isActive == true) return
        historyLoadJob = viewModelScope.launch { fetchHistoryNextPage(portfolioId) }
    }

    // ── Annulation d'un ordre actif (docs/write-actions.md) ──────────────────────────────────

    /**
     * Tap sur « Annuler » d'un ordre de la liste active. Ne fait rien si une annulation est déjà
     * en attente ou en vol, si l'ordre est déjà marqué « demandé », s'il n'est plus dans la liste
     * affichée ou si son statut n'est pas annulable. Sinon évalue la garde d'écriture (VPN +
     * session + lecture des ordres récente) : bloquée → message clair, aucune feuille, aucun
     * appel ; autorisée → ouvre la confirmation ([OrdersUiState.pendingCancel]).
     */
    fun onCancelClicked(orderId: Long) {
        val state = _uiState.value
        if (state.cancelInFlightId != null || state.pendingCancel != null) return
        if (orderId in state.cancelRequestedIds) return
        val order = (state.active as? OrdersTabState.Success)
            ?.orders
            ?.firstOrNull { it.id == orderId }
            ?: return
        if (!order.status.isCancellable()) return
        when (val gate = evaluateWriteGateUseCase(state.activeSyncedAt, WRITE_MAX_AGE_MS)) {
            is WriteGate.Blocked -> showMessage(gate.message)
            WriteGate.Allowed -> _uiState.update { it.copy(pendingCancel = order) }
        }
    }

    /** Fermeture de la feuille sans confirmation (Annuler / geste retour / clic à côté). */
    fun dismissCancel() {
        _uiState.update { it.copy(pendingCancel = null) }
    }

    /**
     * Biométrie réussie (appelé par `ConfirmActionSheet.onConfirmed`) : envoie la demande
     * d'annulation UNE seule fois. La garde est ré-évaluée juste avant l'envoi (la biométrie prend
     * du temps). La ligne passe à « Annulation demandée » ; l'ordre n'est retiré de la liste que si
     * la relecture serveur le confirme. Jamais de nouvel essai automatique.
     */
    fun confirmCancel() {
        val state = _uiState.value
        val order = state.pendingCancel ?: return
        if (state.cancelInFlightId != null) {
            _uiState.update { it.copy(pendingCancel = null) }
            return
        }
        val gate = evaluateWriteGateUseCase(state.activeSyncedAt, WRITE_MAX_AGE_MS)
        if (gate is WriteGate.Blocked) {
            _uiState.update { it.copy(pendingCancel = null, message = gate.message) }
            return
        }
        _uiState.update {
            it.copy(
                pendingCancel = null,
                cancelInFlightId = order.id,
                cancelRequestedIds = it.cancelRequestedIds + order.id,
            )
        }
        val portfolioId = state.portfolioId
        viewModelScope.launch { performCancel(order, portfolioId) }
    }

    fun onMessageShown(message: String) {
        _uiState.update { if (it.message == message) it.copy(message = null) else it }
    }

    private suspend fun performCancel(order: Order, portfolioId: String) {
        try {
            val result = cancelOrderUseCase(order.id)
            val failure = result.exceptionOrNull()
            if (failure != null) {
                onCancelFailed(order, portfolioId, failure)
            } else {
                // Succès sans valeur exploitable → on suppose le cas le moins affirmatif.
                onCancelRequested(order, portfolioId, result.getOrNull() ?: WriteOutcome.REQUESTED_UNCONFIRMED)
            }
        } finally {
            _uiState.update {
                if (it.cancelInFlightId == order.id) it.copy(cancelInFlightId = null) else it
            }
        }
    }

    /** `CONFIRMED` ou `REQUESTED_UNCONFIRMED` : dans les deux cas, on relit l'état serveur. */
    private suspend fun onCancelRequested(order: Order, portfolioId: String, outcome: WriteOutcome) {
        showMessage(cancelRequestedMessage(outcome))
        val reread = loadActive(portfolioId, silent = true)
        if (isStale(portfolioId)) return
        if (reread == null) {
            // Relecture impossible : l'ordre reste « Annulation demandée » jusqu'à une lecture réussie.
            showMessage(CANCEL_VERIFICATION_FAILED_MESSAGE)
        } else {
            showMessage(cancelRereadMessage(order.id, reread))
        }
    }

    /** Échec certain (409, 400, VPN…) : rien n'a été annulé, la ligne redevient annulable. */
    private suspend fun onCancelFailed(order: Order, portfolioId: String, error: Throwable) {
        _uiState.update { it.copy(cancelRequestedIds = it.cancelRequestedIds - order.id) }
        showMessage(cancelFailureMessage(error))
        if (error is HttpStatusException && error.code == HTTP_CONFLICT) {
            // 409 : l'état affiché était périmé (ordre exécuté / déjà annulé) → on le relit.
            loadActive(portfolioId, silent = true)
        }
    }

    private fun showMessage(text: String) {
        _uiState.update { it.copy(message = text) }
    }

    // ── Chargements ──────────────────────────────────────────────────────────────────────────

    /**
     * Lit les ordres actifs de [portfolioId]. [silent] : ne passe pas l'onglet en `Loading` et
     * garde la liste affichée en cas d'échec (relecture après une annulation : la liste ne doit pas
     * clignoter). Succès → [OrdersUiState.activeSyncedAt] = maintenant et les marques « annulation
     * demandée » sont vidées (le serveur fait foi). Retourne la liste lue, `null` en cas d'échec ou
     * si le portefeuille actif a changé entre-temps (réponse écartée).
     */
    private suspend fun loadActive(portfolioId: String, silent: Boolean = false): List<Order>? {
        if (!silent) _uiState.update { it.copy(active = OrdersTabState.Loading) }
        val result = getActiveOrdersUseCase(portfolioId)
        if (isStale(portfolioId)) return null
        val orders = result.getOrNull()
        if (orders != null) {
            _uiState.update {
                it.copy(
                    active = OrdersTabState.Success(orders),
                    activeSyncedAt = System.currentTimeMillis(),
                    cancelRequestedIds = emptySet(),
                )
            }
            return orders
        }
        if (!silent) {
            val message = result.exceptionOrNull()?.localizedMessage ?: "Erreur"
            _uiState.update { it.copy(active = OrdersTabState.Error(message)) }
        }
        return null
    }

    private suspend fun fetchHistoryFirstPage(portfolioId: String) {
        historyOffset = 0
        allHistoryOrders.clear()
        _uiState.update { it.copy(history = OrdersTabState.Loading) }
        loadHistoryPage(portfolioId)
    }

    private suspend fun fetchHistoryNextPage(portfolioId: String) {
        _uiState.update { current ->
            val history = current.history
            if (history is OrdersTabState.Success) {
                current.copy(history = history.copy(isLoadingMore = true))
            } else {
                current
            }
        }
        loadHistoryPage(portfolioId)
    }

    private suspend fun loadHistoryPage(portfolioId: String) {
        getOrderHistoryUseCase(portfolioId, limit = historyPageSize, offset = historyOffset)
            .onSuccess { page ->
                if (isStale(portfolioId)) return@onSuccess
                allHistoryOrders.addAll(page.items)
                historyOffset += page.items.size
                val deduped = allHistoryOrders.distinctBy { it.id }
                allHistoryOrders.clear()
                allHistoryOrders.addAll(deduped)
                _uiState.update {
                    it.copy(
                        history = OrdersTabState.Success(
                            orders = allHistoryOrders.toList(),
                            hasMore = allHistoryOrders.size < page.total,
                            isLoadingMore = false,
                        ),
                    )
                }
            }
            .onFailure { e ->
                if (isStale(portfolioId)) return@onFailure
                _uiState.update {
                    it.copy(history = OrdersTabState.Error(e.localizedMessage ?: "Erreur"))
                }
            }
    }

    /**
     * Vrai si [portfolioId] n'est plus le portefeuille actif : la réponse d'un chargement lancé pour
     * l'ancien portefeuille (rafraîchissement, « Charger plus », relecture après annulation) est
     * alors écartée, sans dépendre du seul timing d'annulation des coroutines.
     */
    private fun isStale(portfolioId: String): Boolean = _uiState.value.portfolioId != portfolioId

    private companion object {
        const val PORTFOLIO_NOT_FOUND_MESSAGE = "Portfolio introuvable"

        /** Âge maximal de la lecture des ordres actifs pour autoriser une annulation. */
        const val WRITE_MAX_AGE_MS = EvaluateWriteGateUseCase.DEFAULT_MAX_AGE_MS

        const val HTTP_CONFLICT = 409
    }
}
