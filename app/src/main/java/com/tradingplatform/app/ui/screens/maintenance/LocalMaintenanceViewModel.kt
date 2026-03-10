package com.tradingplatform.app.ui.screens.maintenance

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.domain.model.DeviceLocalStatus
import com.tradingplatform.app.domain.usecase.maintenance.GetLocalStatusUseCase
import com.tradingplatform.app.domain.usecase.maintenance.SendLocalCommandUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── UiState ────────────────────────────────────────────────────────────────────

sealed interface MaintenanceUiState {
    data object Loading : MaintenanceUiState
    data class Ready(val status: DeviceLocalStatus) : MaintenanceUiState
    data class CommandResult(val message: String) : MaintenanceUiState
    data class Error(val message: String) : MaintenanceUiState
}

// ── ViewModel ──────────────────────────────────────────────────────────────────

/**
 * ViewModel pour [LocalMaintenanceScreen].
 *
 * Reçoit le [deviceId] via [SavedStateHandle] (navigation args).
 * Charge le local_token et la clé publique WireGuard depuis [EncryptedDataStore].
 * Expose un [StateFlow] immuable [uiState].
 *
 * L'IP du device est passée en argument de navigation ("deviceIp") car elle peut
 * varier d'une session à l'autre (DHCP LAN). Si absente, l'état passe en Error.
 */
@HiltViewModel
class LocalMaintenanceViewModel @Inject constructor(
    private val sendLocalCommandUseCase: SendLocalCommandUseCase,
    private val getLocalStatusUseCase: GetLocalStatusUseCase,
    private val dataStore: EncryptedDataStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val deviceId: String = savedStateHandle["deviceId"] ?: ""

    private val _uiState = MutableStateFlow<MaintenanceUiState>(MaintenanceUiState.Loading)
    val uiState: StateFlow<MaintenanceUiState> = _uiState.asStateFlow()

    // Données LAN résolues au démarrage depuis EncryptedDataStore
    private var deviceIp: String = ""
    private var devicePort: Int = 8099
    private var localToken: String = ""
    private var radxaWgPubkey: String = ""

    init {
        viewModelScope.launch {
            localToken = dataStore.readLocalToken(deviceId) ?: ""
            radxaWgPubkey = dataStore.readString("device_wg_pubkey_$deviceId") ?: ""
            deviceIp = dataStore.readString("device_local_ip_$deviceId") ?: ""
            refreshStatus()
        }
    }

    fun refreshStatus() {
        if (deviceIp.isEmpty()) {
            _uiState.value = MaintenanceUiState.Error(
                "IP du device inconnue. Vérifiez que le device est sur le même réseau local."
            )
            return
        }
        viewModelScope.launch {
            _uiState.value = MaintenanceUiState.Loading
            getLocalStatusUseCase(deviceIp, devicePort)
                .onSuccess { _uiState.value = MaintenanceUiState.Ready(it) }
                .onFailure {
                    _uiState.value = MaintenanceUiState.Error(
                        it.localizedMessage ?: "Impossible de contacter le device"
                    )
                }
        }
    }

    fun sendCommand(action: String, params: Map<String, String> = emptyMap()) {
        if (deviceIp.isEmpty()) {
            _uiState.value = MaintenanceUiState.Error(
                "IP du device inconnue. Impossible d'envoyer la commande."
            )
            return
        }
        viewModelScope.launch {
            _uiState.value = MaintenanceUiState.Loading
            sendLocalCommandUseCase(
                deviceIp = deviceIp,
                devicePort = devicePort,
                action = action,
                localToken = localToken,
                radxaWgPubkey = radxaWgPubkey,
                params = params,
            )
                .onSuccess { _uiState.value = MaintenanceUiState.CommandResult(it) }
                .onFailure {
                    _uiState.value = MaintenanceUiState.Error(
                        it.localizedMessage ?: "Erreur lors de l'envoi de la commande"
                    )
                }
        }
    }

    /** Permet de définir l'IP du device si elle est passée via navigation args. */
    fun setDeviceIp(ip: String) {
        deviceIp = ip
    }
}
