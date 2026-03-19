package com.tradingplatform.app.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.exception.AccountLockedException
import com.tradingplatform.app.domain.exception.InvalidCredentialsException
import com.tradingplatform.app.domain.exception.NoPortfolioException
import com.tradingplatform.app.domain.exception.TotpRequiredException
import com.tradingplatform.app.domain.usecase.auth.ApplyAdminWidgetVisibilityUseCase
import com.tradingplatform.app.domain.usecase.auth.GetPortfoliosUseCase
import com.tradingplatform.app.domain.usecase.auth.LoginUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface LoginUiState {
    data object Idle : LoginUiState
    data object Loading : LoginUiState

    /**
     * 2FA requis — naviguer vers TotpScreen avec le [sessionToken].
     * Ce state est émis une seule fois puis réinitialisé via [resetState] après navigation.
     */
    data class TotpRequired(val sessionToken: String) : LoginUiState

    /**
     * Login complet (sans TOTP ou après vérification TOTP).
     * Navigate vers DashboardScreen.
     */
    data object Success : LoginUiState

    data class Error(
        val message: String,
        val retryAfterSeconds: Int? = null,
    ) : LoginUiState
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val loginUseCase: LoginUseCase,
    private val getPortfoliosUseCase: GetPortfoliosUseCase,
    private val applyAdminWidgetVisibilityUseCase: ApplyAdminWidgetVisibilityUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    /**
     * Déclenche le flux de login.
     *
     * Flow :
     * 1. POST /v1/auth/login
     *    - AUTH_1004 → émettre [LoginUiState.TotpRequired] (navigation vers TotpScreen)
     *    - AUTH_1001 → [LoginUiState.Error] "Email ou mot de passe incorrect"
     *    - AUTH_1008 / 429 → [LoginUiState.Error] avec [LoginUiState.Error.retryAfterSeconds]
     *    - Succès sans TOTP → aller en étape 2
     * 2. GET /v1/portfolios
     *    - Liste vide → logout forcé (état incohérent)
     *    - Succès → [LoginUiState.Success]
     */
    fun login(email: String, password: String) {
        if (_uiState.value is LoginUiState.Loading) return

        viewModelScope.launch {
            _uiState.value = LoginUiState.Loading

            loginUseCase(email, password)
                .onSuccess { (user, _) ->
                    if (user.totpEnabled) {
                        // Ne devrait pas arriver ici en pratique : si totpEnabled,
                        // le serveur retourne AUTH_1004 avant d'émettre les tokens.
                        // Ce cas est gardé en sécurité si l'API change de comportement.
                        _uiState.value = LoginUiState.Error("Configuration 2FA inattendue")
                        return@launch
                    }
                    // Pas de TOTP — récupérer le portfolioId puis naviguer vers Dashboard
                    fetchPortfoliosAndSucceed()
                }
                .onFailure { error ->
                    when (error) {
                        is TotpRequiredException -> {
                            _uiState.value = LoginUiState.TotpRequired(error.sessionToken)
                        }
                        is InvalidCredentialsException -> {
                            _uiState.value = LoginUiState.Error("Email ou mot de passe incorrect")
                        }
                        is AccountLockedException -> {
                            val message = if (error.retryAfterSeconds != null) {
                                "Compte verrouillé. Réessayez dans ${error.retryAfterSeconds} secondes."
                            } else {
                                "Compte verrouillé. Réessayez plus tard."
                            }
                            _uiState.value = LoginUiState.Error(
                                message = message,
                                retryAfterSeconds = error.retryAfterSeconds,
                            )
                        }
                        else -> {
                            _uiState.value = LoginUiState.Error(
                                error.localizedMessage ?: "Erreur de connexion"
                            )
                        }
                    }
                }
        }
    }

    /**
     * Remet le state à [LoginUiState.Idle].
     * À appeler depuis le Composable après navigation (TotpRequired ou Success)
     * pour éviter de re-déclencher la navigation lors des recompositions.
     */
    fun resetState() {
        _uiState.value = LoginUiState.Idle
    }

    private suspend fun fetchPortfoliosAndSucceed() {
        getPortfoliosUseCase()
            .onSuccess { portfolios ->
                if (portfolios.isEmpty()) {
                    _uiState.value = LoginUiState.Error("Aucun portfolio trouvé")
                    return
                }
                // Appliquer la visibilité des widgets admin après que portfolioId est stocké.
                // is_admin est persisté dans EncryptedDataStore par LoginUseCase — on le relit
                // ici pour couvrir le chemin login direct (sans TOTP).
                applyAdminWidgetVisibilityUseCase()
                _uiState.value = LoginUiState.Success
            }
            .onFailure { error ->
                if (error is NoPortfolioException) {
                    _uiState.value = LoginUiState.Error("Aucun portfolio trouvé")
                } else {
                    _uiState.value = LoginUiState.Error(
                        error.localizedMessage ?: "Impossible de charger le portfolio"
                    )
                }
            }
    }

}
