package com.tradingplatform.app.ui.screens.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.model.Alert
import com.tradingplatform.app.domain.model.AlertType
import com.tradingplatform.app.domain.usecase.alerts.GetAlertsUseCase
import com.tradingplatform.app.domain.usecase.alerts.GetFilteredAlertsUseCase
import com.tradingplatform.app.domain.usecase.alerts.MarkAlertReadUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
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
            _selectedTypes
                .flatMapLatest { types ->
                    if (types.isEmpty()) {
                        getAlertsUseCase()
                    } else {
                        getFilteredAlertsUseCase(types)
                    }
                }
                .catch { e ->
                    _uiState.value = AlertsUiState.Error(
                        e.localizedMessage ?: "Impossible de charger les alertes"
                    )
                }
                .collect { alerts ->
                    _uiState.value = AlertsUiState.Success(
                        alerts = alerts,
                        unreadCount = alerts.count { !it.read },
                        activeFilter = _selectedTypes.value,
                    )
                }
        }
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
}
