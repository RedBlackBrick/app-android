package com.tradingplatform.app.ui.screens.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.model.Device
import com.tradingplatform.app.domain.usecase.device.GetDevicesUseCase
import com.tradingplatform.app.domain.usecase.device.GetDeviceStatusUseCase
import com.tradingplatform.app.domain.usecase.device.SendDeviceCommandUseCase
import com.tradingplatform.app.domain.usecase.device.UnpairDeviceUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── DevicesUiState — liste des devices ────────────────────────────────────────

sealed interface DevicesUiState {
    data object Loading : DevicesUiState
    data class Success(val devices: List<Device>, val syncedAt: Long) : DevicesUiState
    data class Error(val message: String) : DevicesUiState
}

// ── DeviceDetailUiState — détail d'un device ──────────────────────────────────

sealed interface DeviceDetailUiState {
    data object Loading : DeviceDetailUiState
    data class Success(val device: Device, val syncedAt: Long) : DeviceDetailUiState
    data class Error(val message: String) : DeviceDetailUiState
}

// ── UnpairState — état de l'opération de désappairage ─────────────────────────

sealed interface UnpairState {
    data object Idle : UnpairState
    data object Confirming : UnpairState
    data object InProgress : UnpairState
    data object Success : UnpairState
    data class Error(val message: String) : UnpairState
}

// ── CommandType — types de commandes envoyables à un device ───────────────────

enum class CommandType(val apiValue: String) {
    REBOOT("reboot"),
    HEALTH_CHECK("health_check"),
    UPDATE_FIRMWARE("update_firmware"),
}

// ── CommandState — état de l'envoi d'une commande ─────────────────────────────

sealed interface CommandState {
    data object Idle : CommandState
    data class Confirming(val commandType: CommandType) : CommandState
    data object InProgress : CommandState
    data class Success(val commandType: CommandType) : CommandState
    data class Error(val message: String) : CommandState
}

// ── DevicesViewModel — liste ──────────────────────────────────────────────────

/**
 * ViewModel pour [DeviceListScreen].
 *
 * Charge la liste des devices via [GetDevicesUseCase].
 * Expose un [StateFlow] immuable [uiState].
 * Réservé aux comptes admin (vérification au niveau NavGraph — Phase 8).
 */
@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val getDevicesUseCase: GetDevicesUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<DevicesUiState>(DevicesUiState.Loading)
    val uiState: StateFlow<DevicesUiState> = _uiState.asStateFlow()

    init {
        loadDevices()
    }

    fun loadDevices() {
        viewModelScope.launch {
            _uiState.value = DevicesUiState.Loading
            getDevicesUseCase()
                .onSuccess { devices ->
                    _uiState.value = DevicesUiState.Success(
                        devices = devices,
                        syncedAt = System.currentTimeMillis(),
                    )
                }
                .onFailure { e ->
                    _uiState.value = DevicesUiState.Error(
                        e.localizedMessage ?: "Erreur lors du chargement des devices"
                    )
                }
        }
    }

    fun refresh() = loadDevices()
}

// ── DeviceDetailViewModel — détail ────────────────────────────────────────────

/**
 * ViewModel pour [DeviceDetailScreen] et [EdgeDeviceDashboardScreen].
 *
 * Charge l'état d'un device spécifique via [GetDeviceStatusUseCase].
 * Gère le désappairage via [UnpairDeviceUseCase].
 * Gère les commandes device (reboot, health check, update firmware) via [SendDeviceCommandUseCase].
 * Le [deviceId] est passé en paramètre de chaque méthode (navigation args).
 */
@HiltViewModel
class DeviceDetailViewModel @Inject constructor(
    private val getDeviceStatusUseCase: GetDeviceStatusUseCase,
    private val unpairDeviceUseCase: UnpairDeviceUseCase,
    private val sendDeviceCommandUseCase: SendDeviceCommandUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<DeviceDetailUiState>(DeviceDetailUiState.Loading)
    val uiState: StateFlow<DeviceDetailUiState> = _uiState.asStateFlow()

    private val _unpairState = MutableStateFlow<UnpairState>(UnpairState.Idle)
    val unpairState: StateFlow<UnpairState> = _unpairState.asStateFlow()

    private val _commandState = MutableStateFlow<CommandState>(CommandState.Idle)
    val commandState: StateFlow<CommandState> = _commandState.asStateFlow()

    fun loadDevice(deviceId: String) {
        viewModelScope.launch {
            _uiState.value = DeviceDetailUiState.Loading
            getDeviceStatusUseCase(deviceId)
                .onSuccess { device ->
                    _uiState.value = DeviceDetailUiState.Success(
                        device = device,
                        syncedAt = System.currentTimeMillis(),
                    )
                }
                .onFailure { e ->
                    _uiState.value = DeviceDetailUiState.Error(
                        e.localizedMessage ?: "Erreur lors du chargement du device"
                    )
                }
        }
    }

    fun refresh(deviceId: String) = loadDevice(deviceId)

    // ── Unpair ────────────────────────────────────────────────────────────────

    fun requestUnpair() {
        _unpairState.value = UnpairState.Confirming
    }

    fun cancelUnpair() {
        _unpairState.value = UnpairState.Idle
    }

    fun confirmUnpair(deviceId: String) {
        viewModelScope.launch {
            _unpairState.value = UnpairState.InProgress
            unpairDeviceUseCase(deviceId)
                .onSuccess {
                    _unpairState.value = UnpairState.Success
                }
                .onFailure { e ->
                    _unpairState.value = UnpairState.Error(
                        e.localizedMessage ?: "Erreur lors du désappairage"
                    )
                }
        }
    }

    fun resetUnpairState() {
        _unpairState.value = UnpairState.Idle
    }

    // ── Commandes device ──────────────────────────────────────────────────────

    /**
     * Demande confirmation pour les commandes destructives (REBOOT, UPDATE_FIRMWARE).
     * Pour HEALTH_CHECK, passe directement en [CommandState.Confirming] mais sans dialog bloquant
     * — le screen décide de confirmer immédiatement si HEALTH_CHECK.
     */
    fun requestCommand(commandType: CommandType) {
        _commandState.value = CommandState.Confirming(commandType)
    }

    fun cancelCommand() {
        _commandState.value = CommandState.Idle
    }

    fun sendCommand(deviceId: String, commandType: CommandType) {
        viewModelScope.launch {
            _commandState.value = CommandState.InProgress
            sendDeviceCommandUseCase(deviceId, commandType.apiValue)
                .onSuccess {
                    _commandState.value = CommandState.Success(commandType)
                }
                .onFailure { e ->
                    _commandState.value = CommandState.Error(
                        e.localizedMessage ?: "Erreur lors de l'envoi de la commande"
                    )
                }
        }
    }

    fun resetCommandState() {
        _commandState.value = CommandState.Idle
    }
}
