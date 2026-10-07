package com.tradingplatform.app.ui.screens.vigie

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.model.Vigie
import com.tradingplatform.app.domain.usecase.vigie.GetVigieUseCase
import com.tradingplatform.app.ui.common.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * État de l'écran Vigie.
 *
 * - [Success.syncedAt] = instant RÉEL de la dernière sync du cache (jamais « maintenant ») ;
 * - [Success.staleError] : le dernier rafraîchissement a échoué mais une valeur (périmée) reste
 *   affichée — hors ligne = cache daté, pas d'erreur bloquante ;
 * - [Error] : aucune valeur en cache ET lecture en échec.
 */
sealed interface VigieUiState {
    data object Loading : VigieUiState

    data class Success(
        val vigie: Vigie,
        val syncedAt: Long,
        val isRefreshing: Boolean = false,
        val staleError: String? = null,
    ) : VigieUiState

    data class Error(val message: String) : VigieUiState
}

/**
 * ViewModel de l'écran détail Vigie. Aucun réglage de compte ne conditionne cet écran (donnée
 * publique, accessible depuis la carte comme depuis le widget).
 */
@HiltViewModel
class VigieViewModel @Inject constructor(
    private val getVigieUseCase: GetVigieUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<VigieUiState>(VigieUiState.Loading)
    val uiState: StateFlow<VigieUiState> = _uiState.asStateFlow()

    /** Lecture en cours — garde : un « pull-to-refresh » pendant une lecture est ignoré. */
    private var loadJob: Job? = null

    init {
        load(forceRefresh = false)
    }

    /** Pull-to-refresh / « Réessayer » : ignore la fraîcheur du cache. */
    fun refresh() = load(forceRefresh = true)

    private fun load(forceRefresh: Boolean) {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            val previous = _uiState.value
            _uiState.value = when (previous) {
                is VigieUiState.Success -> previous.copy(isRefreshing = true)
                is VigieUiState.Error -> VigieUiState.Loading
                VigieUiState.Loading -> VigieUiState.Loading
            }
            getVigieUseCase(forceRefresh)
                .onSuccess { cached ->
                    _uiState.value = VigieUiState.Success(vigie = cached.value, syncedAt = cached.syncedAt)
                }
                .onFailure { e ->
                    Timber.w(e, "VigieViewModel: vigie fetch failed")
                    val message = e.toUserMessage("Vigie indisponible")
                    _uiState.value = when (previous) {
                        is VigieUiState.Success ->
                            previous.copy(isRefreshing = false, staleError = message)
                        else -> VigieUiState.Error(message)
                    }
                }
        }
    }
}
