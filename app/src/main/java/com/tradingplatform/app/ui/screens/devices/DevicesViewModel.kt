package com.tradingplatform.app.ui.screens.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.model.Device
import com.tradingplatform.app.domain.usecase.device.GetDevicesUseCase
import com.tradingplatform.app.domain.usecase.device.GetDeviceStatusUseCase
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
 * ViewModel pour [DeviceDetailScreen].
 *
 * Charge l'état d'un device spécifique via [GetDeviceStatusUseCase].
 * Le [deviceId] est passé via [androidx.lifecycle.SavedStateHandle] (navigation args).
 */
@HiltViewModel
class DeviceDetailViewModel @Inject constructor(
    private val getDeviceStatusUseCase: GetDeviceStatusUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<DeviceDetailUiState>(DeviceDetailUiState.Loading)
    val uiState: StateFlow<DeviceDetailUiState> = _uiState.asStateFlow()

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
}
