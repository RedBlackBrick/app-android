package com.tradingplatform.app.ui.screens.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.model.Alert
import com.tradingplatform.app.domain.model.AlertType
import com.tradingplatform.app.domain.usecase.alerts.GetAlertsUseCase
import com.tradingplatform.app.domain.usecase.alerts.GetFilteredAlertsUseCase
import com.tradingplatform.app.domain.usecase.alerts.MarkAlertReadUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

// ── UiState ───────────────────────────────────────────────────────────────────

sealed interface AlertsUiState {
    data object Loading : AlertsUiState
    data class Success(
        val alerts: List<Alert>,
        val unreadCount: Int,
        val activeFilter: Set<AlertType>,
    ) : AlertsUiState
    data class Error(val message: String) : AlertsUiState
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AlertsViewModel @Inject constructor(
    private val getAlertsUseCase: GetAlertsUseCase,
    private val getFilteredAlertsUseCase: GetFilteredAlertsUseCase,
    private val markAlertReadUseCase: MarkAlertReadUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<AlertsUiState>(AlertsUiState.Loading)
    val uiState: StateFlow<AlertsUiState> = _uiState.asStateFlow()

    private val _selectedTypes = MutableStateFlow<Set<AlertType>>(emptySet())
    val selectedTypes: StateFlow<Set<AlertType>> = _selectedTypes.asStateFlow()

    init {
        viewModelScope.launch {
            // Error handling lives INSIDE the flatMapLatest lambda: a terminal `.catch` on the
            // outer chain would complete the whole pipeline on the first Room error, and later
            // filter changes would never re-subscribe (audit C-alerts-corr-1). Here only the
            // inner (per-filter) flow ends in Error; `_selectedTypes` keeps driving the chain.
            _selectedTypes
                .flatMapLatest { types -> alertsStateFor(types) }
                .collect { state -> _uiState.value = state }
        }
    }

    /**
     * Builds the UiState flow for one filter selection. Transient upstream failures are
     * retried [MAX_RETRIES] times with exponential backoff before surfacing an Error.
     * [CancellationException] is never retried (flatMapLatest cancels the previous inner
     * flow on each filter change). The use case is invoked lazily inside `flow {}` so a
     * synchronous throw is caught too and each retry opens a fresh Room query.
     */
    private fun alertsStateFor(types: Set<AlertType>): Flow<AlertsUiState> =
        flow {
            emitAll(if (types.isEmpty()) getAlertsUseCase() else getFilteredAlertsUseCase(types))
        }
            .map<List<Alert>, AlertsUiState> { alerts ->
                AlertsUiState.Success(
                    alerts = alerts,
                    unreadCount = alerts.count { !it.read },
                    activeFilter = types,
                )
            }
            .retryWhen { cause, attempt ->
                if (cause is CancellationException || attempt >= MAX_RETRIES) {
                    false
                } else {
                    Timber.w(cause, "Alerts flow failed — retry ${attempt + 1}/$MAX_RETRIES")
                    delay(RETRY_BASE_DELAY_MS shl attempt.toInt())
                    true
                }
            }
            .catch { e ->
                emit(AlertsUiState.Error(e.localizedMessage ?: "Impossible de charger les alertes"))
            }

    /**
     * Updates the type filter. Pass an empty set to show all alerts (no filter).
     */
    fun setTypeFilter(types: Set<AlertType>) {
        _selectedTypes.value = types
    }

    /**
     * Marks the alert with [alertId] as read. Errors are logged but do not disrupt the UI —
     * a failed mark-as-read is non-critical.
     */
    fun markAsRead(alertId: Long) {
        viewModelScope.launch {
            markAlertReadUseCase(alertId)
                .onFailure { e ->
                    Timber.e(e, "markAsRead failed for alertId=$alertId")
                }
        }
    }

    companion object {
        /** Retries per filter subscription before surfacing [AlertsUiState.Error]. */
        const val MAX_RETRIES = 3L

        /** Backoff base: 500 ms, 1 s, 2 s. */
        const val RETRY_BASE_DELAY_MS = 500L
    }
}
