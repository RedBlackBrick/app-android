package com.tradingplatform.app.ui.screens.risk

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.exception.HttpStatusException
import com.tradingplatform.app.domain.model.RiskStatus
import com.tradingplatform.app.domain.usecase.portfolio.ObserveActivePortfolioUseCase
import com.tradingplatform.app.domain.usecase.portfolio.ObservePortfoliosUseCase
import com.tradingplatform.app.domain.usecase.risk.ActivatePortfolioKillSwitchUseCase
import com.tradingplatform.app.domain.usecase.risk.GetRiskStatusUseCase
import com.tradingplatform.app.domain.usecase.write.EvaluateWriteGateUseCase
import com.tradingplatform.app.domain.usecase.write.WriteGate
import com.tradingplatform.app.ui.common.DataState
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

// ── UiState ─────────────────────────────────────────────────────────────────────

/**
 * État de l'écran Risque.
 *
 * - [risk] : dernière lecture de l'état de risque du portefeuille actif (`DataState` : la valeur
 *   n'est jamais perdue pendant un refresh ; [DataState.syncedAt] alimente `CacheTimestamp` ET la
 *   garde d'écriture).
 * - [isWriting] : une activation de kill switch est en vol (écriture + relecture) → bouton désactivé.
 * - [pendingConfirmation] : confirmation à afficher (`ConfirmActionSheet`), `null` sinon.
 * - [message] : message ponctuel (snackbar) ; consommé par [RiskViewModel.onMessageShown].
 */
data class RiskUiState(
    val portfolioId: String = "",
    val risk: DataState<RiskStatus> = DataState(isRefreshing = true),
    val isWriting: Boolean = false,
    val pendingConfirmation: ConfirmAction? = null,
    val message: String? = null,
) {
    /** « Suspendre le trading » n'est proposé que si l'état est lu ET le kill switch inactif. */
    val canOfferSuspend: Boolean
        get() = risk.value?.killSwitchActive == false
}

// ── ViewModel ───────────────────────────────────────────────────────────────────

/**
 * ViewModel de l'écran Risque du portefeuille ACTIF.
 *
 * Écriture (activation du kill switch, **jamais sa levée**) selon `docs/write-actions.md` :
 * garde d'écriture → confirmation (motif obligatoire + biométrie, dans `ConfirmActionSheet`) →
 * UN seul appel de [ActivatePortfolioKillSwitchUseCase] (jamais rejoué) → RELECTURE de l'état
 * serveur ; le message final n'est émis qu'après la relecture.
 *
 * Changement de portefeuille actif : tout l'état est remis à zéro AVANT le rechargement (jamais de
 * données de l'ancien portefeuille). Une écriture déjà partie n'est pas annulée : le bouton reste
 * désactivé jusqu'à sa fin et son résultat est signalé sans toucher à l'état du nouveau portefeuille.
 */
@HiltViewModel
class RiskViewModel internal constructor(
    private val observeActivePortfolioUseCase: ObserveActivePortfolioUseCase,
    private val observePortfoliosUseCase: ObservePortfoliosUseCase,
    private val getRiskStatusUseCase: GetRiskStatusUseCase,
    private val activatePortfolioKillSwitchUseCase: ActivatePortfolioKillSwitchUseCase,
    private val evaluateWriteGate: EvaluateWriteGateUseCase,
    private val clock: () -> Long,
) : ViewModel() {

    @Inject
    constructor(
        observeActivePortfolioUseCase: ObserveActivePortfolioUseCase,
        observePortfoliosUseCase: ObservePortfoliosUseCase,
        getRiskStatusUseCase: GetRiskStatusUseCase,
        activatePortfolioKillSwitchUseCase: ActivatePortfolioKillSwitchUseCase,
        evaluateWriteGate: EvaluateWriteGateUseCase,
    ) : this(
        observeActivePortfolioUseCase = observeActivePortfolioUseCase,
        observePortfoliosUseCase = observePortfoliosUseCase,
        getRiskStatusUseCase = getRiskStatusUseCase,
        activatePortfolioKillSwitchUseCase = activatePortfolioKillSwitchUseCase,
        evaluateWriteGate = evaluateWriteGate,
        clock = System::currentTimeMillis,
    )

    private val _uiState = MutableStateFlow(RiskUiState())
    val uiState: StateFlow<RiskUiState> = _uiState.asStateFlow()

    /** Lecture en cours (initiale ou pull-to-refresh) — annulée par la suivante ou au changement de portefeuille. */
    private var loadJob: Job? = null

    /**
     * Numéro de la dernière lecture lancée : une lecture dont le numéro n'est plus le courant
     * (relecture post-écriture, changement de portefeuille) est ignorée à son retour — une lecture
     * antérieure à l'écriture ne peut pas écraser l'état relu.
     */
    private var fetchGeneration = 0

    /** Une activation est en vol (écriture + relecture). Thread principal uniquement. */
    private var writeInFlight = false

    init {
        viewModelScope.launch {
            // collectLatest : un changement de portefeuille actif remplace le traitement de l'ancien.
            observeActivePortfolioUseCase().collectLatest { portfolioId -> onActivePortfolio(portfolioId) }
        }
    }

    // ── Lecture ───────────────────────────────────────────────────────────────

    private fun onActivePortfolio(portfolioId: String) {
        loadJob?.cancel()
        fetchGeneration++
        // Remise à zéro AVANT le rechargement : ni données, ni confirmation, ni message de l'ancien.
        _uiState.value = RiskUiState(portfolioId = portfolioId, isWriting = writeInFlight)
        if (portfolioId.isBlank()) {
            _uiState.update { it.copy(risk = it.risk.failure(RISK_PORTFOLIO_MISSING_MESSAGE)) }
            return
        }
        loadJob = viewModelScope.launch { fetchRisk(portfolioId) }
    }

    /** Pull-to-refresh / « Réessayer ». Sans effet pendant une écriture (sa relecture est en cours). */
    fun refresh() {
        val portfolioId = _uiState.value.portfolioId
        if (portfolioId.isBlank() || writeInFlight) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch { fetchRisk(portfolioId) }
    }

    /**
     * Lit l'état de risque de [portfolioId] et l'applique à l'écran.
     *
     * @return le résultat de la lecture, ou `null` si elle a été remplacée entre-temps (autre lecture
     *   ou autre portefeuille) — son résultat n'a alors pas été appliqué.
     */
    private suspend fun fetchRisk(portfolioId: String): Result<RiskStatus>? {
        val generation = ++fetchGeneration
        _uiState.update { it.copy(risk = it.risk.loading()) }
        val result = getRiskStatusUseCase(portfolioId)
        if (generation != fetchGeneration) return null
        result
            .onSuccess { status ->
                _uiState.update { state ->
                    state.copy(
                        risk = state.risk.success(status, clock()),
                        // Kill switch devenu actif pendant que la confirmation était ouverte : plus d'action.
                        pendingConfirmation = if (status.killSwitchActive) null else state.pendingConfirmation,
                    )
                }
            }
            .onFailure { error ->
                _uiState.update { it.copy(risk = it.risk.failure(riskLoadErrorMessage(error))) }
            }
        return result
    }

    // ── Écriture : activation du kill switch ──────────────────────────────────

    /**
     * Tap sur « Suspendre le trading » : garde d'écriture (VPN + session + donnée fraîche) AVANT
     * d'ouvrir la confirmation. Garde bloquée → message, aucune confirmation, aucune écriture.
     * Sans effet si l'état n'est pas lu, si le kill switch est déjà actif ou si une écriture est en vol.
     */
    fun onSuspendClicked() {
        val state = _uiState.value
        if (writeInFlight || state.pendingConfirmation != null || state.portfolioId.isBlank()) return
        val status = state.risk.value ?: return
        if (status.killSwitchActive) return
        when (val gate = evaluateGate(state)) {
            WriteGate.Allowed -> _uiState.update {
                it.copy(pendingConfirmation = killSwitchConfirmAction(portfolioName(state.portfolioId)))
            }
            is WriteGate.Blocked -> _uiState.update { it.copy(message = gate.message) }
        }
    }

    /** La feuille de confirmation est fermée sans confirmation (retour, glissement, « Annuler »). */
    fun onConfirmationDismissed() {
        _uiState.update { it.copy(pendingConfirmation = null) }
    }

    /**
     * Confirmation biométrique réussie (callback de `ConfirmActionSheet`, motif nettoyé).
     *
     * Ignorée sans confirmation en attente (callback tardif). Refusée sans écriture si le motif est
     * vide, si le kill switch est déjà actif ou si la garde est bloquée (réévaluée : la biométrie
     * prend du temps). Sinon UN seul appel d'écriture, suivi de la relecture.
     */
    fun onSuspendConfirmed(reason: String?) {
        val state = _uiState.value
        if (state.pendingConfirmation == null) return
        _uiState.update { it.copy(pendingConfirmation = null) }
        if (writeInFlight) return
        val status = state.risk.value
        if (state.portfolioId.isBlank() || status == null) return
        if (status.killSwitchActive) {
            _uiState.update { it.copy(message = KILL_SWITCH_ALREADY_ACTIVE_MESSAGE) }
            return
        }
        val trimmedReason = reason?.trim().orEmpty()
        if (trimmedReason.isEmpty()) {
            _uiState.update { it.copy(message = KILL_SWITCH_REASON_REQUIRED_MESSAGE) }
            return
        }
        val gate = evaluateGate(state)
        if (gate is WriteGate.Blocked) {
            _uiState.update { it.copy(message = gate.message) }
            return
        }

        writeInFlight = true
        _uiState.update { it.copy(isWriting = true) }
        val portfolioId = state.portfolioId
        val portfolioName = portfolioName(portfolioId)
        viewModelScope.launch { activate(portfolioId, portfolioName, trimmedReason) }
    }

    private suspend fun activate(portfolioId: String, portfolioName: String?, reason: String) {
        // UN seul appel, jamais rejoué : le résultat (succès ou échec) est traité tel quel.
        val result = activatePortfolioKillSwitchUseCase(portfolioId, reason)
        val error = result.exceptionOrNull()
        if (error == null) {
            if (_uiState.value.portfolioId != portfolioId) {
                finishWrite(killSwitchOtherPortfolioMessage(portfolioName))
                return
            }
            // CONFIRMED comme REQUESTED_UNCONFIRMED : relecture, puis message d'après l'état relu.
            val reread = fetchRisk(portfolioId)
            if (_uiState.value.portfolioId != portfolioId) {
                finishWrite(killSwitchOtherPortfolioMessage(portfolioName))
                return
            }
            finishWrite(killSwitchOutcomeMessage(reread?.getOrNull()?.killSwitchActive))
        } else {
            Timber.w("Kill switch activation failed: %s", error.javaClass.simpleName)
            // Un 409 invite explicitement à relire l'état ; les autres échecs sont certains.
            if (error is HttpStatusException && error.code == HTTP_CONFLICT &&
                _uiState.value.portfolioId == portfolioId
            ) {
                fetchRisk(portfolioId)
            }
            finishWrite(killSwitchFailureMessage(error))
        }
    }

    private fun finishWrite(message: String) {
        writeInFlight = false
        _uiState.update { it.copy(isWriting = false, message = message) }
    }

    /** Le snackbar a été montré : on consomme le message. */
    fun onMessageShown() {
        _uiState.update { it.copy(message = null) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun evaluateGate(state: RiskUiState): WriteGate =
        evaluateWriteGate(
            state.risk.syncedAt.takeIf { it > 0L },
            EvaluateWriteGateUseCase.DEFAULT_MAX_AGE_MS,
        )

    private fun portfolioName(portfolioId: String): String? =
        observePortfoliosUseCase().value.firstOrNull { it.id == portfolioId }?.name

    private companion object {
        const val HTTP_CONFLICT = 409
    }
}
