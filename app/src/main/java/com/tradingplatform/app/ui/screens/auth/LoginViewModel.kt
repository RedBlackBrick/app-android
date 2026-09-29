package com.tradingplatform.app.ui.screens.auth

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.exception.AccountLockedException
import com.tradingplatform.app.domain.exception.InvalidCredentialsException
import com.tradingplatform.app.domain.exception.NoPortfolioException
import com.tradingplatform.app.domain.exception.TotpRequiredException
import com.tradingplatform.app.data.session.SessionManager
import com.tradingplatform.app.domain.usecase.auth.ApplyAdminWidgetVisibilityUseCase
import com.tradingplatform.app.domain.usecase.auth.GetPortfoliosUseCase
import com.tradingplatform.app.domain.usecase.auth.LoginUseCase
import com.tradingplatform.app.domain.usecase.vpn.GetVpnConsentIntentUseCase
import com.tradingplatform.app.domain.usecase.vpn.HasVpnConfigUseCase
import com.tradingplatform.app.domain.usecase.vpn.ObserveVpnStateUseCase
import com.tradingplatform.app.domain.usecase.vpn.ReconnectVpnUseCase
import com.tradingplatform.app.domain.usecase.vpn.RetryVpnAfterConsentUseCase
import com.tradingplatform.app.ui.screens.settings.VpnConsentUiState
import com.tradingplatform.app.vpn.VpnState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

private const val LOGIN_TIMEOUT_MS = 30_000L

sealed interface LoginUiState {
    data object Idle : LoginUiState
    data object Loading : LoginUiState

    /**
     * 2FA requis — naviguer vers TotpScreen.
     * Le sessionToken est stocké dans [SessionManager] (jamais dans la route de navigation).
     * Ce state est émis une seule fois puis réinitialisé via [resetState] après navigation.
     */
    data object TotpRequired : LoginUiState

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
    private val sessionManager: SessionManager,
    observeVpnStateUseCase: ObserveVpnStateUseCase,
    private val hasVpnConfigUseCase: HasVpnConfigUseCase,
    private val reconnectVpnUseCase: ReconnectVpnUseCase,
    private val getVpnConsentIntentUseCase: GetVpnConsentIntentUseCase,
    private val retryVpnAfterConsentUseCase: RetryVpnAfterConsentUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    /**
     * État VPN **affiché** (tunnel intégré ou VPN système tiers) — alimente le bandeau de
     * l'écran. Le bouton « Se connecter » reste actif quel que soit cet état : la détection peut
     * se tromper et l'intercepteur réseau reste le vrai garde.
     */
    val vpnState: StateFlow<VpnState> = observeVpnStateUseCase()

    private val _hasVpnConfig = MutableStateFlow(false)

    /** Une configuration WireGuard est persistée : « Activer le VPN » peut relancer le tunnel. */
    val hasVpnConfig: StateFlow<Boolean> = _hasVpnConfig.asStateFlow()

    private val _consentState = MutableStateFlow<VpnConsentUiState>(VpnConsentUiState.None)

    /**
     * Consentement VPN d'Android (`VpnService.prepare`), même modèle que `VpnSettingsViewModel`
     * ([VpnConsentUiState]) : `Required` → l'écran lance le dialogue système une fois.
     */
    val consentState: StateFlow<VpnConsentUiState> = _consentState.asStateFlow()

    /** L'utilisateur a demandé « Activer le VPN » : autorise le lancement auto du consentement. */
    private var activationRequested = false

    init {
        viewModelScope.launch { _hasVpnConfig.value = hasVpnConfigUseCase() }
        viewModelScope.launch {
            vpnState.collect { state ->
                if (state !is VpnState.Connecting && state !is VpnState.ConsentRequired) {
                    activationRequested = false
                }
                _consentState.update { current -> nextVpnConsentState(current, state, activationRequested) }
            }
        }
    }

    /**
     * « Activer le VPN » : relance le tunnel depuis la configuration persistée. Si Android doit
     * d'abord autoriser l'app (`ConsentRequired`), demande le dialogue système à la place (le
     * rejouer ne ferait que republier `ConsentRequired`). Sans effet si le tunnel est déjà actif
     * ou en cours d'établissement.
     */
    fun onActivateVpn() {
        when (vpnState.value) {
            is VpnState.ConsentRequired -> requestVpnConsent()
            is VpnState.Connecting, is VpnState.Connected, is VpnState.SystemVpnActive -> Unit
            is VpnState.Disconnected, is VpnState.Error -> {
                activationRequested = true
                reconnectVpnUseCase()
            }
        }
    }

    /** Intent du dialogue de consentement VPN d'Android, ou null si déjà accordé. */
    fun vpnConsentIntent(): Intent? = getVpnConsentIntentUseCase()

    /** L'écran a lancé le dialogue : empêche un second lancement à la recomposition. */
    fun onVpnConsentLaunched() {
        _consentState.update { if (it == VpnConsentUiState.Required) VpnConsentUiState.Launched else it }
    }

    /**
     * Résultat du dialogue (RESULT_OK → [granted]). Accordé : la connexion arrêtée sur
     * `ConsentRequired` est rejouée. Refusé : [VpnConsentUiState.Denied] (message explicite +
     * « Réessayer »).
     */
    fun onVpnConsentResult(granted: Boolean) {
        val current = _consentState.value
        if (current != VpnConsentUiState.Required && current != VpnConsentUiState.Launched) return
        if (granted) {
            _consentState.value = VpnConsentUiState.None
            retryVpnAfterConsentUseCase()
        } else {
            _consentState.value = VpnConsentUiState.Denied
        }
    }

    private fun requestVpnConsent() {
        activationRequested = true
        _consentState.update {
            if (it == VpnConsentUiState.None || it == VpnConsentUiState.Denied) VpnConsentUiState.Required else it
        }
    }

    /**
     * Déclenche le flux de login.
     *
     * Flow :
     * 1. POST /v1/auth/login
     *    - AUTH_1004 → émettre [LoginUiState.TotpRequired] (navigation vers TotpScreen)
     *    - AUTH_1001 → [LoginUiState.Error] "Email ou mot de passe incorrect"
     *    - AUTH_1008 / 429 → [LoginUiState.Error] avec [LoginUiState.Error.retryAfterSeconds]
     *    - échec réseau (délai, connexion refusée, hôte introuvable) → « VPN requis — activez le
     *      tunnel puis réessayez » si [vpnState] n'est pas actif (voir [loginNetworkErrorMessage])
     *    - Succès sans TOTP → aller en étape 2
     * 2. GET /v1/portfolios
     *    - Liste vide → logout forcé (état incohérent)
     *    - Succès → [LoginUiState.Success]
     */
    fun login(email: String, password: String) {
        if (_uiState.value is LoginUiState.Loading) return

        viewModelScope.launch {
            _uiState.value = LoginUiState.Loading

            val result = withTimeoutOrNull(LOGIN_TIMEOUT_MS) {
                loginUseCase(email, password)
            }
            if (result == null) {
                // Sans tunnel, /auth/login (chemin public, non bloqué par l'intercepteur VPN) part
                // vers le VPS et s'éteint en délai d'attente : on désigne le VPN, pas le serveur.
                _uiState.value = LoginUiState.Error(loginTimeoutMessage(vpnState.value))
                return@launch
            }
            result
                .onSuccess { (user, _) ->
                    if (user.totpEnabled) {
                        // Ne devrait pas arriver ici en pratique : si totpEnabled,
                        // le serveur retourne AUTH_1004 avant d'émettre les tokens.
                        // Ce cas est gardé en sécurité si l'API change de comportement.
                        _uiState.value = LoginUiState.Error("Configuration 2FA inattendue")
                        return@launch
                    }
                    // Pas de TOTP — récupérer le portfolioId puis naviguer vers Dashboard
                    fetchPortfoliosAndSucceed(user.isAdmin)
                }
                .onFailure { error ->
                    when (error) {
                        is TotpRequiredException -> {
                            sessionManager.storePendingTotpToken(error.sessionToken)
                            _uiState.value = LoginUiState.TotpRequired
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
                                loginNetworkErrorMessage(error, vpnState.value)
                                    ?: error.localizedMessage
                                    ?: "Erreur de connexion"
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

    private suspend fun fetchPortfoliosAndSucceed(isAdmin: Boolean) {
        getPortfoliosUseCase()
            .onSuccess { portfolios ->
                if (portfolios.isEmpty()) {
                    _uiState.value = LoginUiState.Error("Aucun portfolio trouvé")
                    return
                }
                // Appliquer la visibilité des widgets admin après que portfolioId est stocké.
                applyAdminWidgetVisibilityUseCase(isAdmin)
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
