package com.tradingplatform.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.security.BiometricLockManager
import com.tradingplatform.app.security.BiometricManager
import com.tradingplatform.app.security.RootDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for SecuritySettingsScreen.
 *
 * Loads device security status at startup:
 * - Biometric availability via [BiometricManager.isAvailable]
 * - Root detection via [RootDetector.isRooted]
 *
 * The [EncryptedDataStore] is injected for potential future use (e.g., reading stored
 * security preferences). It is not used in the current implementation but is part of the
 * constructor contract defined in CLAUDE.md §7S.
 */
@HiltViewModel
class SecuritySettingsViewModel @Inject constructor(
    private val biometricManager: BiometricManager,
    private val rootDetector: RootDetector,
    private val lockManager: BiometricLockManager,
) : ViewModel() {

    /**
     * UI state for the security settings screen.
     *
     * @param isBiometricAvailable true if the device has enrolled biometrics and they are available.
     * @param isRooted true if RootBeer detects root indicators on the device.
     * @param isLoading true while the security check is in progress.
     */
    data class SecurityUiState(
        val isBiometricAvailable: Boolean = false,
        val isRooted: Boolean = false,
        val isLoading: Boolean = true,
        val lockTimeoutMs: Long = BiometricLockManager.INACTIVITY_TIMEOUT_MS,
    )

    private val _uiState = MutableStateFlow(SecurityUiState())
    val uiState: StateFlow<SecurityUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            lockManager.timeoutMs.collect { timeout ->
                _uiState.value = _uiState.value.copy(lockTimeoutMs = timeout)
            }
        }
        viewModelScope.launch {
            // BiometricManager.isAvailable() is a synchronous call (no suspend).
            // RootDetector.isRooted() is also synchronous.
            // Both are dispatched on the coroutine's default dispatcher (Main in viewModelScope),
            // which is acceptable since neither performs I/O — they query system APIs only.
            val biometricAvailable = biometricManager.isAvailable()
            val rooted = rootDetector.isRooted()
            _uiState.value = _uiState.value.copy(
                isBiometricAvailable = biometricAvailable,
                isRooted = rooted,
                isLoading = false,
            )
        }
    }

    fun onLockTimeoutSelected(timeoutMs: Long) {
        viewModelScope.launch { lockManager.setInactivityTimeout(timeoutMs) }
    }
}
