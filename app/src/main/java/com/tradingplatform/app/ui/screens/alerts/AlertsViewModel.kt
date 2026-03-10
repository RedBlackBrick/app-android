package com.tradingplatform.app.ui.screens.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.model.Alert
import com.tradingplatform.app.domain.usecase.alerts.GetAlertsUseCase
import com.tradingplatform.app.domain.usecase.alerts.MarkAlertReadUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── UiState ───────────────────────────────────────────────────────────────────

sealed interface AlertsUiState {
    data object Loading : AlertsUiState
    data class Success(val alerts: List<Alert>, val unreadCount: Int) : AlertsUiState
    data class Error(val message: String) : AlertsUiState
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class AlertsViewModel @Inject constructor(
    private val getAlertsUseCase: GetAlertsUseCase,
    private val markAlertReadUseCase: MarkAlertReadUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<AlertsUiState>(AlertsUiState.Loading)
    val uiState: StateFlow<AlertsUiState> = _uiState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        viewModelScope.launch {
            getAlertsUseCase()
                .catch { e ->
                    _uiState.value = AlertsUiState.Error(
                        e.localizedMessage ?: "Impossible de charger les alertes"
                    )
                }
                .collect { alerts ->
                    _uiState.value = AlertsUiState.Success(
                        alerts = alerts,
                        unreadCount = alerts.count { !it.read },
                    )
                }
        }
    }

    /**
     * Marks the alert with [alertId] as read. Errors are silently swallowed — a failed
     * mark-as-read is non-critical and should not disrupt the UI.
     */
    fun markAsRead(alertId: Long) {
        viewModelScope.launch {
            markAlertReadUseCase(alertId)
        }
    }

    /**
     * Visual pull-to-refresh affordance. Room Flow auto-updates, so this just
     * shows the refresh indicator briefly for UX consistency with other screens.
     */
    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            delay(400)
            _isRefreshing.value = false
        }
    }
}
