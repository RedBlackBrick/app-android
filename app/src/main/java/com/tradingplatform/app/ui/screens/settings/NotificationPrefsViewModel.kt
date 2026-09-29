package com.tradingplatform.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.exception.HttpStatusException
import com.tradingplatform.app.domain.model.NotifCategory
import com.tradingplatform.app.domain.model.NotificationPreferences
import com.tradingplatform.app.domain.usecase.notification.GetNotificationPreferencesUseCase
import com.tradingplatform.app.domain.usecase.notification.SetPushCategoryUseCase
import com.tradingplatform.app.domain.usecase.write.EvaluateWriteGateUseCase
import com.tradingplatform.app.domain.usecase.write.WriteGate
import com.tradingplatform.app.ui.common.DataState
import com.tradingplatform.app.ui.components.ConfirmAction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

// ── UiState ─────────────────────────────────────────────────────────────────────

/**
 * État de l'écran Notifications.
 *
 * - [prefs] : dernière lecture des préférences (`DataState`). Les interrupteurs affichent TOUJOURS
 *   cet état relu, jamais un état espéré (pas d'optimisme) ; [DataState.syncedAt] alimente
 *   `CacheTimestamp` et la garde d'écriture.
 * - [pendingChange] : changement en attente de confirmation (feuille ouverte), `null` sinon.
 * - [savingCategory] : catégorie dont l'écriture (+ relecture) est en vol ; tous les interrupteurs
 *   sont alors désactivés (une seule écriture à la fois).
 * - [message] : message ponctuel (snackbar), consommé par [NotificationPrefsViewModel.onMessageShown].
 */
data class NotificationPrefsUiState(
    val prefs: DataState<NotificationPreferences> = DataState(isRefreshing = true),
    val pendingChange: PushChange? = null,
    val savingCategory: NotifCategory? = null,
    val message: String? = null,
) {
    /** Confirmation à afficher (`ConfirmActionSheet`), dérivée du changement en attente. */
    val pendingConfirmation: ConfirmAction?
        get() = pendingChange?.let { pushChangeConfirmAction(it.category, it.enabled) }
}

// ── ViewModel ───────────────────────────────────────────────────────────────────

/**
 * ViewModel de l'écran Notifications (Réglages → Notifications).
 *
 * Changement d'un interrupteur push, selon `docs/write-actions.md` : garde d'écriture (donnée
 * fraîche) → confirmation biométrique (`ConfirmActionSheet` sans motif) → UN seul appel de
 * [SetPushCategoryUseCase] (jamais rejoué) → RELECTURE des préférences ; l'interrupteur ne bouge
 * qu'avec l'état relu, et le message final n'est émis qu'après la relecture.
 */
@HiltViewModel
class NotificationPrefsViewModel internal constructor(
    private val getNotificationPreferencesUseCase: GetNotificationPreferencesUseCase,
    private val setPushCategoryUseCase: SetPushCategoryUseCase,
    private val evaluateWriteGate: EvaluateWriteGateUseCase,
    private val clock: () -> Long,
) : ViewModel() {

    @Inject
    constructor(
        getNotificationPreferencesUseCase: GetNotificationPreferencesUseCase,
        setPushCategoryUseCase: SetPushCategoryUseCase,
        evaluateWriteGate: EvaluateWriteGateUseCase,
    ) : this(
        getNotificationPreferencesUseCase = getNotificationPreferencesUseCase,
        setPushCategoryUseCase = setPushCategoryUseCase,
        evaluateWriteGate = evaluateWriteGate,
        clock = System::currentTimeMillis,
    )

    private val _uiState = MutableStateFlow(NotificationPrefsUiState())
    val uiState: StateFlow<NotificationPrefsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    /** Dernière lecture lancée : une lecture remplacée (relecture post-écriture) est ignorée à son retour. */
    private var fetchGeneration = 0

    /** Une écriture (+ relecture) est en vol. Thread principal uniquement. */
    private var writeInFlight = false

    init {
        loadJob = viewModelScope.launch { fetchPrefs() }
    }

    // ── Lecture ───────────────────────────────────────────────────────────────

    /** Pull-to-refresh / « Réessayer ». Sans effet pendant une écriture (sa relecture est en cours). */
    fun refresh() {
        if (writeInFlight) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch { fetchPrefs() }
    }

    /**
     * Lit les préférences et les applique à l'écran.
     *
     * @return le résultat de la lecture, ou `null` si elle a été remplacée entre-temps (son résultat
     *   n'a alors pas été appliqué).
     */
    private suspend fun fetchPrefs(): Result<NotificationPreferences>? {
        val generation = ++fetchGeneration
        _uiState.update { it.copy(prefs = it.prefs.loading()) }
        val result = getNotificationPreferencesUseCase()
        if (generation != fetchGeneration) return null
        result
            .onSuccess { prefs ->
                _uiState.update { it.copy(prefs = it.prefs.success(prefs, clock())) }
            }
            .onFailure { error ->
                _uiState.update { it.copy(prefs = it.prefs.failure(prefsLoadErrorMessage(error))) }
            }
        return result
    }

    // ── Écriture : interrupteur push d'une catégorie ──────────────────────────

    /**
     * Tap sur l'interrupteur push de [category] (valeur demandée [enabled]) : garde d'écriture AVANT
     * d'ouvrir la confirmation. Garde bloquée → message, aucune confirmation, aucune écriture.
     * Sans effet si les préférences ne sont pas lues, si la valeur demandée est déjà l'état lu ou si
     * une écriture est en vol.
     */
    fun onPushToggleRequested(category: NotifCategory, enabled: Boolean) {
        val state = _uiState.value
        if (writeInFlight || state.pendingChange != null) return
        val prefs = state.prefs.value ?: return
        if (prefs.isPushEnabled(category) == enabled) return
        when (val gate = evaluateGate(state)) {
            WriteGate.Allowed -> _uiState.update { it.copy(pendingChange = PushChange(category, enabled)) }
            is WriteGate.Blocked -> _uiState.update { it.copy(message = gate.message) }
        }
    }

    /** La feuille de confirmation est fermée sans confirmation. */
    fun onChangeDismissed() {
        _uiState.update { it.copy(pendingChange = null) }
    }

    /**
     * Confirmation biométrique réussie. Ignorée sans changement en attente (callback tardif). La
     * garde est réévaluée (la biométrie prend du temps) ; sinon UN seul appel d'écriture, suivi de
     * la relecture.
     */
    fun onChangeConfirmed() {
        val state = _uiState.value
        val change = state.pendingChange ?: return
        _uiState.update { it.copy(pendingChange = null) }
        if (writeInFlight) return
        val prefs = state.prefs.value ?: return
        // Déjà dans l'état demandé (relu entre-temps) : rien à écrire.
        if (prefs.isPushEnabled(change.category) == change.enabled) return
        val gate = evaluateGate(state)
        if (gate is WriteGate.Blocked) {
            _uiState.update { it.copy(message = gate.message) }
            return
        }

        writeInFlight = true
        _uiState.update { it.copy(savingCategory = change.category) }
        viewModelScope.launch { write(change) }
    }

    private suspend fun write(change: PushChange) {
        // UN seul appel, jamais rejoué : le résultat (succès ou échec) est traité tel quel.
        val result = setPushCategoryUseCase(change.category, change.enabled)
        val error = result.exceptionOrNull()
        if (error == null) {
            // CONFIRMED comme REQUESTED_UNCONFIRMED : relecture, puis message d'après l'état relu.
            val reread = fetchPrefs()
            val rereadEnabled = reread?.getOrNull()?.isPushEnabled(change.category)
            finishWrite(pushOutcomeMessage(change.category, change.enabled, rereadEnabled))
        } else {
            Timber.w("Push preference change failed: %s", error.javaClass.simpleName)
            // Un 409 invite explicitement à relire l'état ; les autres échecs sont certains.
            if (error is HttpStatusException && error.code == HTTP_CONFLICT) {
                fetchPrefs()
            }
            finishWrite(pushFailureMessage(error))
        }
    }

    private fun finishWrite(message: String) {
        writeInFlight = false
        _uiState.update { it.copy(savingCategory = null, message = message) }
    }

    /** Le snackbar a été montré : on consomme le message. */
    fun onMessageShown() {
        _uiState.update { it.copy(message = null) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun evaluateGate(state: NotificationPrefsUiState): WriteGate =
        evaluateWriteGate(
            state.prefs.syncedAt.takeIf { it > 0L },
            EvaluateWriteGateUseCase.DEFAULT_MAX_AGE_MS,
        )

    private companion object {
        const val HTTP_CONFLICT = 409
    }
}
