package com.tradingplatform.app.ui.screens.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.domain.usecase.pairing.ParseSetupQrUseCase
import com.tradingplatform.app.vpn.VpnState
import com.tradingplatform.app.vpn.WireGuardManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * UiState for [SetupScreen].
 *
 * Lifecycle:
 * - [Scanning]   — waiting for the user to scan the onboarding QR code
 * - [Connecting] — QR parsed, WireGuard tunnel being established
 * - [Connected]  — tunnel up, navigating to Login
 * - [Error]      — QR parse failure or VPN error, retryable via [SetupViewModel.retry]
 */
sealed interface SetupUiState {
    data object Scanning : SetupUiState
    data object Connecting : SetupUiState
    data object Connected : SetupUiState
    data class Error(val message: String) : SetupUiState
}

/**
 * ViewModel for the initial onboarding setup screen.
 *
 * Handles QR parsing and WireGuard tunnel setup. Once [VpnState.Connected] is observed,
 * persists [DataStoreKeys.SETUP_COMPLETED] and transitions to [SetupUiState.Connected]
 * so the Composable can navigate to [LoginScreen].
 *
 * Security invariants:
 * - [ParseSetupQrUseCase] never logs the private key — [REDACTED].
 * - [WireGuardManager.configureFromSetupQr] stores the key in EncryptedDataStore, never in memory.
 */
@HiltViewModel
class SetupViewModel @Inject constructor(
    private val parseSetupQrUseCase: ParseSetupQrUseCase,
    private val wireGuardManager: WireGuardManager,
    private val dataStore: EncryptedDataStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow<SetupUiState>(SetupUiState.Scanning)
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    /**
     * Called by the [QrScannerView] callback when a QR code has been detected.
     *
     * Idempotent — if already past [SetupUiState.Scanning], the call is ignored
     * (QrScannerView fires the callback only once per scan, but guard here as well).
     */
    fun onQrScanned(raw: String) {
        if (_uiState.value !is SetupUiState.Scanning) return

        viewModelScope.launch {
            parseSetupQrUseCase(raw)
                .onSuccess { setupData ->
                    _uiState.value = SetupUiState.Connecting

                    // configureFromSetupQr stores the private key in EncryptedDataStore
                    // and calls connect() — private key is [REDACTED] in all logs.
                    wireGuardManager.configureFromSetupQr(setupData)

                    // Observe the VPN state until it reaches a terminal state.
                    // first { } suspends until the predicate is true, then returns — avoids
                    // an infinite collect loop that return@collect cannot break out of.
                    val terminalState = wireGuardManager.state.first { vpnState ->
                        vpnState is VpnState.Connected || vpnState is VpnState.Error
                    }
                    when (terminalState) {
                        is VpnState.Connected -> {
                            dataStore.writeBoolean(DataStoreKeys.SETUP_COMPLETED, true)
                            _uiState.value = SetupUiState.Connected
                            Timber.i("SetupViewModel: WireGuard connected, setup_completed=true")
                        }
                        is VpnState.Error -> {
                            _uiState.value = SetupUiState.Error(
                                terminalState.message.ifBlank { "Erreur de connexion VPN" }
                            )
                        }
                        else -> Unit // Cannot happen — first { } only returns Connected or Error
                    }
                }
                .onFailure { error ->
                    Timber.w("SetupViewModel: QR parse failed — ${error.message}")
                    _uiState.value = SetupUiState.Error(
                        error.localizedMessage ?: "QR code non reconnu"
                    )
                }
        }
    }

    /**
     * Resets the state back to [SetupUiState.Scanning] after an error.
     * The QrScannerView will re-arm its scan on the next recomposition.
     */
    fun retry() {
        _uiState.value = SetupUiState.Scanning
    }
}
