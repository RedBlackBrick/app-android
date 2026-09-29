package com.tradingplatform.app.ui.screens.auth

import com.tradingplatform.app.ui.screens.settings.VpnConsentUiState
import com.tradingplatform.app.ui.screens.setup.VPN_CONSENT_DENIED_MESSAGE
import com.tradingplatform.app.vpn.VpnNotConnectedException
import com.tradingplatform.app.vpn.VpnState
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/*
 * Règles pures de l'écran de connexion liées au VPN (testées en JVM) : affichage du bandeau,
 * transitions du consentement Android, message d'erreur d'un login sans tunnel.
 *
 * `/auth/login` est dans `AuthPaths.PUBLIC` : `VpnRequiredInterceptor` ne le bloque pas. Sans
 * tunnel, la requête part vers 10.42.0.1 et se termine en délai d'attente — d'où le mapping
 * explicite ci-dessous, plutôt que le message technique de l'exception.
 */

/** Package de l'app WireGuard officielle (`<queries>` du manifeste requis pour la détecter). */
const val WIREGUARD_PACKAGE = "com.wireguard.android"

const val LOGIN_VPN_REQUIRED_MESSAGE = "VPN requis — activez le tunnel puis réessayez"
const val LOGIN_SERVER_UNREACHABLE_MESSAGE = "Serveur injoignable — réessayez dans un instant"
const val LOGIN_TIMEOUT_MESSAGE = "La connexion a expiré — réessayez"

const val LOGIN_VPN_ACTIVE_TITLE = "VPN actif"
const val LOGIN_VPN_CONNECTING_TITLE = "Connexion au VPN…"
const val LOGIN_VPN_REQUIRED_TITLE = "VPN requis"
const val LOGIN_VPN_REQUIRED_DETAIL = "La connexion à la plateforme n'est possible que via le VPN."
const val LOGIN_VPN_NO_CONFIG_DETAIL =
    "Aucun tunnel n'est configuré dans l'application : activez votre VPN externe."
const val LOGIN_VPN_CONSENT_DETAIL =
    "Android doit d'abord autoriser l'application à créer le tunnel VPN."
const val LOGIN_VPN_ACTIVATE_LABEL = "Activer le VPN"
const val LOGIN_VPN_RETRY_LABEL = "Réessayer"

/** Profondeur maximale de la chaîne de causes examinée pour classer une erreur réseau. */
private const val MAX_CAUSE_DEPTH = 5

enum class LoginVpnBannerKind {
    /** Tunnel intégré ou VPN externe actif : simple pastille « VPN actif ». */
    ACTIVE,

    /** Établissement en cours : « Connexion au VPN… » + indicateur. */
    CONNECTING,

    /** Carte « VPN requis » avec actions. */
    REQUIRED,
}

/**
 * @property detail corps de la carte (uniquement pour [LoginVpnBannerKind.REQUIRED]).
 * @property showActivate afficher le bouton primaire [activateLabel].
 * @property consentDenied l'utilisateur a refusé le dialogue Android : [detail] devient le
 *   message de refus explicite (affiché en couleur d'erreur).
 */
data class LoginVpnBannerModel(
    val kind: LoginVpnBannerKind,
    val title: String,
    val detail: String?,
    val showActivate: Boolean,
    val activateLabel: String,
    val consentDenied: Boolean,
)

/** Tunnel utilisable : intégré `Connected` ou VPN système tiers (`SystemVpnActive`). */
fun isVpnUsable(state: VpnState): Boolean =
    state is VpnState.Connected || state is VpnState.SystemVpnActive

/**
 * Décision d'affichage du bandeau de l'écran de connexion selon l'état VPN **affiché**.
 *
 * @param hasVpnConfig une configuration WireGuard est persistée (sinon « Activer le VPN » ne
 *   peut rien faire — sauf pour `ConsentRequired`, qui implique une configuration déjà connue).
 * @param consentDenied le dialogue de consentement Android vient d'être refusé.
 */
fun loginVpnBanner(
    state: VpnState,
    hasVpnConfig: Boolean,
    consentDenied: Boolean,
): LoginVpnBannerModel = when (state) {
    is VpnState.Connected, is VpnState.SystemVpnActive -> LoginVpnBannerModel(
        kind = LoginVpnBannerKind.ACTIVE,
        title = LOGIN_VPN_ACTIVE_TITLE,
        detail = null,
        showActivate = false,
        activateLabel = LOGIN_VPN_ACTIVATE_LABEL,
        consentDenied = false,
    )
    is VpnState.Connecting -> LoginVpnBannerModel(
        kind = LoginVpnBannerKind.CONNECTING,
        title = LOGIN_VPN_CONNECTING_TITLE,
        detail = null,
        showActivate = false,
        activateLabel = LOGIN_VPN_ACTIVATE_LABEL,
        consentDenied = false,
    )
    is VpnState.ConsentRequired -> LoginVpnBannerModel(
        kind = LoginVpnBannerKind.REQUIRED,
        title = LOGIN_VPN_REQUIRED_TITLE,
        detail = if (consentDenied) LOGIN_VPN_CONSENT_DENIED_DETAIL else LOGIN_VPN_CONSENT_DETAIL,
        showActivate = true,
        activateLabel = if (consentDenied) LOGIN_VPN_RETRY_LABEL else LOGIN_VPN_ACTIVATE_LABEL,
        consentDenied = consentDenied,
    )
    is VpnState.Disconnected, is VpnState.Error -> LoginVpnBannerModel(
        kind = LoginVpnBannerKind.REQUIRED,
        title = LOGIN_VPN_REQUIRED_TITLE,
        detail = if (hasVpnConfig) LOGIN_VPN_REQUIRED_DETAIL else LOGIN_VPN_NO_CONFIG_DETAIL,
        showActivate = hasVpnConfig,
        activateLabel = LOGIN_VPN_ACTIVATE_LABEL,
        consentDenied = false,
    )
}

/** Refus du dialogue Android — même libellé que `SetupScreen` / `VpnSettingsScreen`. */
const val LOGIN_VPN_CONSENT_DENIED_DETAIL = VPN_CONSENT_DENIED_MESSAGE

/**
 * Prochain état du consentement Android sur l'écran de connexion (même machine à états que
 * `VpnSettingsViewModel`, avec une différence : le dialogue système n'est lancé automatiquement
 * en entrant dans `ConsentRequired` que si l'utilisateur a demandé « Activer le VPN » —
 * [activationRequested] — pour ne jamais surprendre à l'ouverture de l'app).
 */
fun nextVpnConsentState(
    current: VpnConsentUiState,
    vpnState: VpnState,
    activationRequested: Boolean,
): VpnConsentUiState = when {
    vpnState is VpnState.ConsentRequired ->
        if (current == VpnConsentUiState.None && activationRequested) VpnConsentUiState.Required else current
    // Résultat du dialogue encore attendu (survit à un changement de configuration).
    current == VpnConsentUiState.Launched -> current
    else -> VpnConsentUiState.None
}

/**
 * Message d'un échec réseau du login, ou `null` si [error] n'est pas un échec réseau (l'appelant
 * garde alors son message habituel).
 *
 * - `VpnNotConnectedException` : toujours « VPN requis » ;
 * - délai d'attente / connexion refusée / hôte introuvable / pas de route :
 *   « VPN requis » si [vpnState] n'est ni `Connected` ni `SystemVpnActive`, sinon
 *   « Serveur injoignable » (le tunnel est là : le problème est ailleurs).
 * La chaîne des causes est examinée (une exception peut être enveloppée).
 */
fun loginNetworkErrorMessage(error: Throwable, vpnState: VpnState): String? {
    var current: Throwable? = error
    var depth = 0
    var networkFailure = false
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        if (current is VpnNotConnectedException) return LOGIN_VPN_REQUIRED_MESSAGE
        if (
            current is SocketTimeoutException ||
            current is ConnectException ||
            current is NoRouteToHostException ||
            current is UnknownHostException
        ) {
            networkFailure = true
        }
        current = current.cause
        depth++
    }
    return when {
        !networkFailure -> null
        isVpnUsable(vpnState) -> LOGIN_SERVER_UNREACHABLE_MESSAGE
        else -> LOGIN_VPN_REQUIRED_MESSAGE
    }
}

/** Message du délai global du login (30 s) selon l'état VPN. */
fun loginTimeoutMessage(vpnState: VpnState): String =
    if (isVpnUsable(vpnState)) LOGIN_TIMEOUT_MESSAGE else LOGIN_VPN_REQUIRED_MESSAGE
