package com.tradingplatform.app.ui.screens.totp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.data.session.SessionManager
import com.tradingplatform.app.domain.exception.InvalidTotpCodeException
import com.tradingplatform.app.domain.exception.NoPortfolioException
import com.tradingplatform.app.domain.usecase.auth.ApplyAdminWidgetVisibilityUseCase
import com.tradingplatform.app.domain.usecase.auth.GetPortfoliosUseCase
import com.tradingplatform.app.domain.usecase.auth.Verify2faUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface TotpUiState {
    data object AwaitingInput : TotpUiState
    data object Verifying : TotpUiState

    /**
     * Vérification 2FA et récupération du portfolio réussies.
     * Le Composable doit naviguer vers DashboardScreen.
     */
    data object Success : TotpUiState

    data class Error(val message: String) : TotpUiState
}

@HiltViewModel
class TotpViewModel @Inject constructor(
    private val verify2faUseCase: Verify2faUseCase,
    private val getPortfoliosUseCase: GetPortfoliosUseCase,
    private val applyAdminWidgetVisibilityUseCase: ApplyAdminWidgetVisibilityUseCase,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow<TotpUiState>(TotpUiState.AwaitingInput)
    val uiState: StateFlow<TotpUiState> = _uiState.asStateFlow()

    /**
     * Vérifie le code TOTP puis récupère le portfolioId.
     *
     * Flow :
     * 1. POST /v1/auth/2fa/verify → succès ou [InvalidTotpCodeException]
     * 2. Si succès → GET /v1/portfolios → stocker portfolioId → [TotpUiState.Success]
     *
     * Le sessionToken est consommé depuis [SessionManager] — il n'est jamais transmis
     * via les routes de navigation pour éviter son exposition dans la backstack.
     *
     * @param totpCode Code à 6 chiffres saisi par l'utilisateur.
     */
    fun verify(totpCode: String) {
        if (_uiState.value is TotpUiState.Verifying) return
        val sessionToken = sessionManager.consumePendingTotpToken() ?: run {
            _uiState.value = TotpUiState.Error("Session expirée. Reconnectez-vous.")
            return
        }

        viewModelScope.launch {
            _uiState.value = TotpUiState.Verifying

            verify2faUseCase(sessionToken, totpCode)
                .onSuccess { (user, _) ->
                    // Vérification réussie — récupérer le portfolioId avant de naviguer
                    fetchPortfoliosAndSucceed(user.isAdmin)
                }
                .onFailure { error ->
                    when (error) {
                        is InvalidTotpCodeException -> {
                            _uiState.value = TotpUiState.Error("Code incorrect. Réessayez.")
                        }
                        else -> {
                            _uiState.value = TotpUiState.Error(
                                error.localizedMessage ?: "Erreur de vérification"
                            )
                        }
                    }
                }
        }
    }

    /**
     * Réinitialise l'état à [TotpUiState.AwaitingInput] après affichage d'une erreur.
     * Permet à l'utilisateur de saisir un nouveau code.
     */
    fun resetError() {
        if (_uiState.value is TotpUiState.Error) {
            _uiState.value = TotpUiState.AwaitingInput
        }
    }

    private suspend fun fetchPortfoliosAndSucceed(isAdmin: Boolean) {
        getPortfoliosUseCase()
            .onSuccess { portfolios ->
                if (portfolios.isEmpty()) {
                    _uiState.value = TotpUiState.Error("Aucun portfolio trouvé")
                    return
                }
                applyAdminWidgetVisibilityUseCase(isAdmin)
                _uiState.value = TotpUiState.Success
            }
            .onFailure { error ->
                if (error is NoPortfolioException) {
                    _uiState.value = TotpUiState.Error("Aucun portfolio trouvé")
                } else {
                    _uiState.value = TotpUiState.Error(
                        error.localizedMessage ?: "Impossible de charger le portfolio"
                    )
                }
            }
    }
}
