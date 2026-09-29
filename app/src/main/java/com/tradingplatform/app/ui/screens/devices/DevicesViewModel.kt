package com.tradingplatform.app.ui.screens.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.model.BrokerConnection
import com.tradingplatform.app.domain.model.Device
import com.tradingplatform.app.domain.usecase.device.GetBrokerConnectionsUseCase
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

// ── BrokerUiState — connexions broker d'un device ───────────────────────────

sealed interface BrokerUiState {
    data object Idle : BrokerUiState
    data object Loading : BrokerUiState
    data class Success(val connections: List<BrokerConnection>) : BrokerUiState
    data class Error(val message: String) : BrokerUiState
}

// ── DevicesViewModel — liste ──────────────────────────────────────────────────

/**
 * ViewModel pour [DeviceListScreen].
 *
 * Charge la liste des devices via [GetDevicesUseCase] (lecture seule : la gestion des devices
 * se fait sur la plateforme web). Expose des [StateFlow] immuables.
 * Réservé aux comptes admin (vérification au niveau NavGraph).
 */
@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val getDevicesUseCase: GetDevicesUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<DevicesUiState>(DevicesUiState.Loading)
    val uiState: StateFlow<DevicesUiState> = _uiState.asStateFlow()

    /** Rechargement en cours alors qu'une liste est déjà affichée (elle reste à l'écran). */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        loadDevices()
    }

    fun loadDevices() {
        viewModelScope.launch {
            // Une liste déjà affichée reste visible pendant le rechargement (pas de squelette).
            if (_uiState.value is DevicesUiState.Success) {
                _isRefreshing.value = true
            } else {
                _uiState.value = DevicesUiState.Loading
            }
            try {
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
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun refresh() = loadDevices()
}

// ── DeviceDetailViewModel — détail ────────────────────────────────────────────

/**
 * ViewModel de l'écran [EdgeDeviceDashboardScreen] (détail d'un device, lecture seule).
 *
 * Charge l'état d'un device via [GetDeviceStatusUseCase] et ses connexions broker via
 * [GetBrokerConnectionsUseCase]. Aucune action de gestion (reboot, firmware, désappairage…) :
 * elles se font sur la plateforme web.
 * Le [deviceId] est passé en paramètre de chaque méthode (navigation args).
 */
@HiltViewModel
class DeviceDetailViewModel @Inject constructor(
    private val getDeviceStatusUseCase: GetDeviceStatusUseCase,
    private val getBrokerConnectionsUseCase: GetBrokerConnectionsUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<DeviceDetailUiState>(DeviceDetailUiState.Loading)
    val uiState: StateFlow<DeviceDetailUiState> = _uiState.asStateFlow()

    /** Rechargement en cours alors que le device est déjà affiché (il reste à l'écran). */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _brokerState = MutableStateFlow<BrokerUiState>(BrokerUiState.Idle)
    val brokerState: StateFlow<BrokerUiState> = _brokerState.asStateFlow()

    /**
     * Charge l'état du device. Sans [forceRefresh], le cache Room est servi s'il est frais
     * (< `CacheTtl.DEVICES_MS`) ; `syncedAt` est l'horodatage réel de la donnée affichée.
     */
    fun loadDevice(deviceId: String, forceRefresh: Boolean = false) {
        viewModelScope.launch {
            val current = _uiState.value
            if (current is DeviceDetailUiState.Success && current.device.id == deviceId) {
                _isRefreshing.value = true
            } else {
                _uiState.value = DeviceDetailUiState.Loading
            }
            try {
                getDeviceStatusUseCase(deviceId, forceRefresh)
                    .onSuccess { cached ->
                        _uiState.value = DeviceDetailUiState.Success(
                            device = cached.value,
                            syncedAt = cached.syncedAt,
                        )
                    }
                    .onFailure { e ->
                        _uiState.value = DeviceDetailUiState.Error(
                            e.localizedMessage ?: "Erreur lors du chargement du device"
                        )
                    }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /** Pull-to-refresh / retry : contourne toujours le cache Room. */
    fun refresh(deviceId: String) = loadDevice(deviceId, forceRefresh = true)

    // ── Broker connections ────────────────────────────────────────────────────

    fun loadBrokerConnections(deviceId: String) {
        viewModelScope.launch {
            // Les connexions déjà affichées restent visibles pendant le rechargement.
            if (_brokerState.value !is BrokerUiState.Success) {
                _brokerState.value = BrokerUiState.Loading
            }
            getBrokerConnectionsUseCase(deviceId)
                .onSuccess { connections ->
                    _brokerState.value = BrokerUiState.Success(connections)
                }
                .onFailure { e ->
                    _brokerState.value = BrokerUiState.Error(
                        e.localizedMessage ?: "Erreur lors du chargement des connexions broker"
                    )
                }
        }
    }
}
