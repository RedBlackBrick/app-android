package com.tradingplatform.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.model.VpnPeer
import com.tradingplatform.app.domain.usecase.device.GetMyDevicesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── UiState ────────────────────────────────────────────────────────────────────

sealed interface MyDevicesUiState {
    data object Loading : MyDevicesUiState
    data class Success(val peers: List<VpnPeer>, val syncedAt: Long) : MyDevicesUiState
    data class Error(val message: String) : MyDevicesUiState
}

// ── ViewModel ──────────────────────────────────────────────────────────────────

@HiltViewModel
class MyDevicesViewModel @Inject constructor(
    private val getMyDevicesUseCase: GetMyDevicesUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<MyDevicesUiState>(MyDevicesUiState.Loading)
    val uiState: StateFlow<MyDevicesUiState> = _uiState.asStateFlow()

    init {
        loadDevices()
    }

    fun loadDevices() {
        viewModelScope.launch {
            _uiState.value = MyDevicesUiState.Loading
            getMyDevicesUseCase()
                .onSuccess {
                    _uiState.value = MyDevicesUiState.Success(
                        peers = it,
                        syncedAt = System.currentTimeMillis(),
                    )
                }
                .onFailure { _uiState.value = MyDevicesUiState.Error(it.localizedMessage ?: "Erreur") }
        }
    }

    fun refresh() = loadDevices()
}
