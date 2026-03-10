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

@HiltViewModel
class MyDevicesViewModel @Inject constructor(
    private val getMyDevicesUseCase: GetMyDevicesUseCase,
) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState
        data class Success(val peers: List<VpnPeer>, val syncedAt: Long) : UiState
        data class Error(val message: String) : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadDevices()
    }

    fun loadDevices() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            getMyDevicesUseCase()
                .onSuccess {
                    _uiState.value = UiState.Success(
                        peers = it,
                        syncedAt = System.currentTimeMillis(),
                    )
                }
                .onFailure { _uiState.value = UiState.Error(it.localizedMessage ?: "Erreur") }
        }
    }

    fun refresh() = loadDevices()
}
