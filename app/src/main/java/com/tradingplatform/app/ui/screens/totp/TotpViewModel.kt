package com.tradingplatform.app.ui.screens.totp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.data.session.SessionManager
import com.tradingplatform.app.domain.exception.AccountLockedException
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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import javax.inject.Inject

private const val TOTP_VERIFY_TIMEOUT_MS = 30_000L

internal const val TOTP_SESSION_LOST_MESSAGE =
    "Code incorrect ou session 2FA expirée — reconnectez-vous"
internal const val TOTP_NO_SESSION_MESSAGE = "Session 2FA expirée — reconnectez-vous"

sealed interface TotpUiState {
    data object AwaitingInput : TotpUiState
    data object Verifying : TotpUiState

    /**
     * Vérification 2FA et récupération du portfolio réussies.
     * Le Composable doit naviguer vers DashboardScreen.
     */
    data object Success : TotpUiState

    /** Erreur récupérable : le temp token est conservé, l'utilisateur peut ressaisir un code. */
    data class Error(val message: String) : TotpUiState

    /**
     * La session 2FA est perdue (401 serveur : code faux ou temp token expiré/consommé,
     * ou aucun temp token en mémoire). Un nouveau code ne peut pas aboutir — l'écran
     * propose « Retour à la connexion ».
     */
    data class BackToLogin(val message: String) : TotpUiState
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
     * Le sessionToken est lu depuis [SessionManager] — il n'est jamais transmis
     * via les routes de navigation pour éviter son exposition dans la backstack.
     *
     * Cycle de vie du temp token (backend : TTL 300 s, supprimé à la première lecture
     * serveur, avant la vérification du code) :
     * - lu sans être consommé ([SessionManager.pendingTotpToken]) ;
     * - consommé sur succès, ou sur [InvalidTotpCodeException] (401 serveur : le token
     *   est brûlé côté backend) → [TotpUiState.BackToLogin] ;
     * - conservé sur timeout / IOException / 429 / autre erreur → [TotpUiState.Error]
     *   récupérable (si le serveur l'a brûlé malgré tout, le retry obtiendra un 401).
     *
     * @param totpCode Code à 6 chiffres saisi par l'utilisateur.
     */
    fun verify(totpCode: String) {
        if (_uiState.value is TotpUiState.Verifying) return
        val sessionToken = sessionManager.pendingTotpToken ?: run {
            _uiState.value = TotpUiState.BackToLogin(TOTP_NO_SESSION_MESSAGE)
            return
        }

        viewModelScope.launch {
            _uiState.value = TotpUiState.Verifying

            val result = withTimeoutOrNull(TOTP_VERIFY_TIMEOUT_MS) {
                verify2faUseCase(sessionToken, totpCode)
            }
            if (result == null) {
                // Timeout local : le token est conservé — retry possible
                _uiState.value = TotpUiState.Error(
                    "La vérification a expiré — vérifiez le VPN et réessayez"
                )
                return@launch
            }
            result
                .onSuccess { (user, _) ->
                    // Session établie — le temp token est désormais inutile
                    sessionManager.consumePendingTotpToken()
                    // Vérification réussie — récupérer le portfolioId avant de naviguer
                    fetchPortfoliosAndSucceed(user.isAdmin)
                }
                .onFailure { error ->
                    when (error) {
                        is InvalidTotpCodeException -> {
                            // 401 serveur : le backend a supprimé le temp token → inutilisable
                            sessionManager.consumePendingTotpToken()
                            _uiState.value = TotpUiState.BackToLogin(TOTP_SESSION_LOST_MESSAGE)
                        }
                        is AccountLockedException -> {
                            _uiState.value = TotpUiState.Error(
                                if (error.retryAfterSeconds != null) {
                                    "Trop de tentatives. Réessayez dans ${error.retryAfterSeconds} secondes."
                                } else {
                                    "Trop de tentatives. Réessayez plus tard."
                                }
                            )
                        }
                        is IOException -> {
                            _uiState.value = TotpUiState.Error(
                                "Erreur réseau — vérifiez le VPN et réessayez"
                            )
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
