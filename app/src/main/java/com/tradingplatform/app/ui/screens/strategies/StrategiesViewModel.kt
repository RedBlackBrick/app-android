package com.tradingplatform.app.ui.screens.strategies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.model.PortfolioStrategyEntry
import com.tradingplatform.app.domain.usecase.portfolio.GetPortfolioStrategiesUseCase
import com.tradingplatform.app.domain.usecase.portfolio.ObserveActivePortfolioUseCase
import com.tradingplatform.app.domain.usecase.portfolio.ObservePortfoliosUseCase
import com.tradingplatform.app.domain.usecase.portfolio.SetPortfolioStrategyActiveUseCase
import com.tradingplatform.app.domain.usecase.write.EvaluateWriteGateUseCase
import com.tradingplatform.app.domain.usecase.write.WriteGate
import com.tradingplatform.app.ui.components.ConfirmAction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** Contenu de la liste des liens du portefeuille actif. */
sealed interface StrategiesContent {
    data object Loading : StrategiesContent
    data class Success(val entries: List<PortfolioStrategyEntry>) : StrategiesContent
    data class Error(val message: String) : StrategiesContent
}

/** Phase d'une écriture en vol : envoi de la demande, puis relecture de l'état serveur. */
enum class StrategyWritePhase { SENDING, VERIFYING }

/**
 * Écriture (pause / réactivation d'un lien) en vol. [portfolioId] est celui de la demande : si le
 * portefeuille actif change pendant l'écriture, elle n'est PAS annulée (son issue serait inconnue)
 * mais son résultat n'est pas appliqué à la liste du nouveau portefeuille.
 */
data class StrategyWriteInFlight(
    val portfolioId: String,
    val strategyId: String,
    val targetActive: Boolean,
    val phase: StrategyWritePhase,
)

/** Confirmation à présenter (feuille + biométrie) pour un lien du portefeuille [portfolioId]. */
data class PendingStrategyConfirmation(
    val portfolioId: String,
    val strategyId: String,
    val targetActive: Boolean,
    val action: ConfirmAction,
)

data class StrategiesUiState(
    val portfolioId: String = "",
    val content: StrategiesContent = StrategiesContent.Loading,
    /** Rafraîchissement en arrière-plan d'une liste déjà affichée (pull-to-refresh). */
    val isRefreshing: Boolean = false,
    /**
     * Instant (epoch ms) de la dernière lecture RÉUSSIE de la liste — alimente `CacheTimestamp` et
     * la garde d'écriture (`dataSyncedAt`). `null` = jamais lue pour ce portefeuille, ou état
     * non vérifié après une écriture dont la relecture a échoué (la garde répond alors « périmée »).
     */
    val syncedAt: Long? = null,
    val write: StrategyWriteInFlight? = null,
    val confirmation: PendingStrategyConfirmation? = null,
    /** Message ponctuel (snackbar) ; consommé via [StrategiesViewModel.messageShown]. */
    val message: String? = null,
)

/**
 * Liste les liens portefeuille-stratégie du portefeuille ACTIF et permet de mettre un lien en
 * pause / de le réactiver (`docs/write-actions.md`) :
 *
 * garde d'écriture → [ConfirmAction] (feuille + biométrie) → [SetPortfolioStrategyActiveUseCase]
 * UNE seule fois (jamais rejoué) → message « demandé / effectué » → RELECTURE de la liste → état
 * réel affiché. Une seule écriture à la fois ; boutons désactivés pendant l'envoi et la relecture.
 *
 * Un changement de portefeuille actif remet l'état à zéro AVANT le rechargement.
 */
@HiltViewModel
class StrategiesViewModel @Inject constructor(
    private val observeActivePortfolioUseCase: ObserveActivePortfolioUseCase,
    private val observePortfoliosUseCase: ObservePortfoliosUseCase,
    private val getPortfolioStrategiesUseCase: GetPortfolioStrategiesUseCase,
    private val setPortfolioStrategyActiveUseCase: SetPortfolioStrategyActiveUseCase,
    private val evaluateWriteGateUseCase: EvaluateWriteGateUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StrategiesUiState())
    val uiState: StateFlow<StrategiesUiState> = _uiState.asStateFlow()

    /** Rafraîchissement manuel en cours (le chargement initial vit dans le `collectLatest`). */
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            // collectLatest : un changement de portefeuille actif annule le chargement en cours.
            observeActivePortfolioUseCase().collectLatest { portfolioId -> onActivePortfolio(portfolioId) }
        }
    }

    /**
     * Nouveau portefeuille actif (dont le premier) : liste, horodatage, confirmation et message
     * sont remis à zéro AVANT le rechargement — jamais de lien de l'ancien portefeuille affiché.
     * Une écriture déjà en vol n'est pas touchée (voir [StrategyWriteInFlight]).
     */
    private suspend fun onActivePortfolio(portfolioId: String) {
        refreshJob?.cancel()
        _uiState.update {
            it.copy(
                portfolioId = portfolioId,
                content = StrategiesContent.Loading,
                isRefreshing = false,
                syncedAt = null,
                confirmation = null,
                message = null,
            )
        }
        if (portfolioId.isBlank()) {
            // Un état local incohérent (aucun portefeuille résolu) ne doit pas partir au réseau.
            _uiState.update { it.copy(content = StrategiesContent.Error(PORTFOLIO_NOT_FOUND_MESSAGE)) }
            return
        }
        loadStrategies(portfolioId)
    }

    /** Pull-to-refresh / « Réessayer ». Sans effet pendant un chargement ou une écriture. */
    fun refresh() {
        val state = _uiState.value
        val portfolioId = state.portfolioId
        if (portfolioId.isBlank() || state.content is StrategiesContent.Loading) return
        if (state.write != null || refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch { loadStrategies(portfolioId) }
    }

    /**
     * Tap sur « Mettre en pause » / « Réactiver ce lien » : évalue la garde d'écriture puis, si elle
     * est [WriteGate.Allowed], expose la confirmation à présenter. `Blocked` → message, aucune
     * feuille, aucune écriture.
     */
    fun requestToggle(strategyId: String) {
        val state = _uiState.value
        val success = state.content as? StrategiesContent.Success ?: return
        if (!canStartStrategyWrite(state.write, state.isRefreshing)) return
        val entry = success.entries.firstOrNull { it.strategyId == strategyId } ?: return

        val gate = evaluateWriteGateUseCase(state.syncedAt, EvaluateWriteGateUseCase.DEFAULT_MAX_AGE_MS)
        if (gate is WriteGate.Blocked) {
            _uiState.update { it.copy(message = gate.message) }
            return
        }

        val target = !entry.isActive
        val portfolioLabel = observePortfoliosUseCase().value
            .firstOrNull { it.id == state.portfolioId }
            ?.name
            ?.takeIf { it.isNotBlank() }
            ?: ACTIVE_PORTFOLIO_FALLBACK_LABEL
        val action = strategyConfirmAction(
            displayName = strategyDisplayName(entry.name, entry.strategyId),
            portfolioLabel = portfolioLabel,
            targetActive = target,
        )
        _uiState.update {
            it.copy(
                confirmation = PendingStrategyConfirmation(
                    portfolioId = state.portfolioId,
                    strategyId = entry.strategyId,
                    targetActive = target,
                    action = action,
                ),
            )
        }
    }

    /** L'utilisateur ferme la feuille de confirmation sans confirmer. */
    fun dismissConfirmation() {
        _uiState.update { it.copy(confirmation = null) }
    }

    /**
     * Appelé UNIQUEMENT depuis le succès du prompt biométrique de la feuille. Sans confirmation
     * en attente (ou déjà consommée) : sans effet — jamais de double envoi. La garde est réévaluée
     * juste avant l'envoi (la biométrie prend du temps) et la ligne doit encore être dans l'état
     * qui avait motivé la demande.
     */
    fun confirmPending() {
        val pending = _uiState.value.confirmation ?: return
        // La feuille exige que le parent efface l'action dans onConfirmed.
        _uiState.update { it.copy(confirmation = null) }

        val state = _uiState.value
        if (!canStartStrategyWrite(state.write, state.isRefreshing)) return
        if (pending.portfolioId != state.portfolioId) return

        val gate = evaluateWriteGateUseCase(state.syncedAt, EvaluateWriteGateUseCase.DEFAULT_MAX_AGE_MS)
        if (gate is WriteGate.Blocked) {
            _uiState.update { it.copy(message = gate.message) }
            return
        }

        val entry = (state.content as? StrategiesContent.Success)
            ?.entries
            ?.firstOrNull { it.strategyId == pending.strategyId }
        if (entry == null || entry.isActive == pending.targetActive) {
            _uiState.update { it.copy(message = STATE_CHANGED_MESSAGE) }
            return
        }

        val write = StrategyWriteInFlight(
            portfolioId = pending.portfolioId,
            strategyId = pending.strategyId,
            targetActive = pending.targetActive,
            phase = StrategyWritePhase.SENDING,
        )
        _uiState.update { it.copy(write = write) }
        viewModelScope.launch { executeWrite(write) }
    }

    /** Le snackbar de [message] a été affiché : ne l'efface que s'il n'a pas été remplacé entre-temps. */
    fun messageShown(message: String) {
        _uiState.update { if (it.message == message) it.copy(message = null) else it }
    }

    // ── Lecture ───────────────────────────────────────────────────────────────

    private suspend fun loadStrategies(portfolioId: String) {
        _uiState.update {
            if (it.content is StrategiesContent.Success) {
                it.copy(isRefreshing = true)
            } else {
                it.copy(content = StrategiesContent.Loading, isRefreshing = false)
            }
        }
        val result = getPortfolioStrategiesUseCase(portfolioId)
        if (isStale(portfolioId)) return
        result
            .onSuccess { entries ->
                _uiState.update {
                    it.copy(
                        content = StrategiesContent.Success(entries),
                        isRefreshing = false,
                        syncedAt = System.currentTimeMillis(),
                    )
                }
            }
            .onFailure { e ->
                Timber.tag(TAG).w("Strategies load failed (%s)", e.javaClass.simpleName)
                val message = strategiesLoadErrorMessage(e)
                _uiState.update {
                    if (it.content is StrategiesContent.Success) {
                        // Valeur périmée conservée (avec son horodatage) + message.
                        it.copy(isRefreshing = false, message = message)
                    } else {
                        it.copy(content = StrategiesContent.Error(message), isRefreshing = false)
                    }
                }
            }
    }

    // ── Écriture ──────────────────────────────────────────────────────────────

    /**
     * Envoie la demande UNE seule fois (aucun retry), annonce « effectuée / demandée », relit la
     * liste et n'affiche l'état final qu'après cette relecture. Les identifiants ne sont jamais
     * loggés.
     */
    private suspend fun executeWrite(write: StrategyWriteInFlight) {
        val outcome = setPortfolioStrategyActiveUseCase(
            write.portfolioId,
            write.strategyId,
            write.targetActive,
        ).getOrElse { e ->
            Timber.tag(TAG).w("Strategy link write failed (%s)", e.javaClass.simpleName)
            val message = if (isStale(write.portfolioId)) {
                PREVIOUS_PORTFOLIO_MESSAGE
            } else {
                strategyWriteFailureMessage(e)
            }
            _uiState.update { it.copy(write = null, message = message) }
            return
        }

        if (isStale(write.portfolioId)) {
            _uiState.update { it.copy(write = null, message = PREVIOUS_PORTFOLIO_MESSAGE) }
            return
        }
        _uiState.update {
            it.copy(
                write = write.copy(phase = StrategyWritePhase.VERIFYING),
                message = strategyWriteSuccessMessage(write.targetActive, outcome),
            )
        }

        // Relecture : seule source de vérité de l'état final (même après un 2xx).
        val reread = getPortfolioStrategiesUseCase(write.portfolioId)
        if (isStale(write.portfolioId)) {
            _uiState.update { it.copy(write = null, message = PREVIOUS_PORTFOLIO_MESSAGE) }
            return
        }
        reread
            .onSuccess { entries ->
                val actual = entries.firstOrNull { it.strategyId == write.strategyId }?.isActive
                val verification = strategyVerificationMessage(write.targetActive, actual, outcome)
                _uiState.update {
                    it.copy(
                        content = StrategiesContent.Success(entries),
                        syncedAt = System.currentTimeMillis(),
                        write = null,
                        message = verification ?: it.message,
                    )
                }
            }
            .onFailure { e ->
                Timber.tag(TAG).w("Strategies re-read failed (%s)", e.javaClass.simpleName)
                // L'état affiché est peut-être en retard sur le serveur : la garde le refusera
                // (« données trop anciennes ») jusqu'à une relecture réussie.
                _uiState.update { it.copy(write = null, syncedAt = null, message = REREAD_FAILED_MESSAGE) }
            }
    }

    /** `true` si [portfolioId] n'est plus le portefeuille actif : sa réponse est écartée. */
    private fun isStale(portfolioId: String): Boolean = _uiState.value.portfolioId != portfolioId

    private companion object {
        const val TAG = "StrategiesVM"
    }
}
