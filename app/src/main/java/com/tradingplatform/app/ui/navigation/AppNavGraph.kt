package com.tradingplatform.app.ui.navigation

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tradingplatform.app.data.session.SessionManager
import com.tradingplatform.app.data.session.TokenHolder
import com.tradingplatform.app.domain.usecase.auth.GetAuthContextUseCase
import com.tradingplatform.app.domain.usecase.auth.LogoutUseCase
import com.tradingplatform.app.domain.usecase.auth.RecoverFromKeystoreCorruptionUseCase
import com.tradingplatform.app.security.BiometricLockManager
import com.tradingplatform.app.security.BiometricManager
import com.tradingplatform.app.ui.components.BiometricLockOverlay
import com.tradingplatform.app.ui.components.VpnStatusBanner
import com.tradingplatform.app.vpn.SystemVpnMonitor
import com.tradingplatform.app.vpn.VpnState
import com.tradingplatform.app.vpn.computeEffectiveVpnState
import com.tradingplatform.app.vpn.WireGuardManager
import com.tradingplatform.app.ui.screens.alerts.AlertListScreen
import com.tradingplatform.app.ui.screens.auth.LoginScreen
import com.tradingplatform.app.ui.screens.dashboard.DashboardScreen
import com.tradingplatform.app.ui.screens.devices.DeviceListScreen
import com.tradingplatform.app.ui.screens.devices.EdgeDeviceDashboardScreen
import com.tradingplatform.app.ui.screens.market.MarketDataScreen
import com.tradingplatform.app.ui.screens.orders.OrdersScreen
import com.tradingplatform.app.ui.screens.performance.PerformanceScreen
import com.tradingplatform.app.ui.screens.pairing.PairingDoneScreen
import com.tradingplatform.app.ui.screens.pairing.PairingProgressScreen
import com.tradingplatform.app.ui.screens.pairing.PairingViewModel
import com.tradingplatform.app.ui.screens.pairing.ScanDeviceQrScreen
import com.tradingplatform.app.ui.screens.pairing.ScanVpsQrScreen
import com.tradingplatform.app.ui.screens.portfolio.PositionDetailScreen
import com.tradingplatform.app.ui.screens.portfolio.PositionsScreen
import com.tradingplatform.app.ui.screens.portfolio.TransactionHistoryScreen
import com.tradingplatform.app.ui.screens.settings.MyDevicesScreen
import com.tradingplatform.app.ui.screens.settings.ProfileScreen
import com.tradingplatform.app.ui.screens.settings.SecuritySettingsScreen
import com.tradingplatform.app.ui.screens.settings.SettingsScreen
import com.tradingplatform.app.ui.screens.settings.VpnSettingsScreen
import com.tradingplatform.app.ui.screens.setup.SetupScreen
import com.tradingplatform.app.ui.screens.totp.TotpScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

// ── UpgradeRequiredDialog ──────────────────────────────────────────────────────

@Composable
private fun UpgradeRequiredDialog() {
    val context = LocalContext.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { /* non-dismissable */ },
        title = {
            androidx.compose.material3.Text("Mise à jour requise")
        },
        text = {
            androidx.compose.material3.Text(
                "Cette version de l'application n'est plus supportée. " +
                    "Veuillez mettre à jour l'application pour continuer."
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                val packageName = context.packageName
                // Try Play Store app first, fall back to browser if Play Store is absent (sideload).
                val marketIntent = Intent(
                    Intent.ACTION_VIEW,
                    "market://details?id=$packageName".toUri(),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(marketIntent)
                } catch (e: ActivityNotFoundException) {
                    Timber.w(e, "UpgradeRequiredDialog: Play Store not available, opening browser")
                    val webIntent = Intent(
                        Intent.ACTION_VIEW,
                        "https://play.google.com/store/apps/details?id=$packageName".toUri(),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(webIntent) }
                        .onFailure { Timber.w(it, "UpgradeRequiredDialog: no browser available") }
                }
            }) {
                androidx.compose.material3.Text("Mettre à jour")
            }
        },
    )
}

// ── KeystoreCorruptionDialog (R1 fix) ────────────────────────────────────────

/**
 * Dialog affiché quand le Keystore Android est corrompu (clés invalides après
 * reboot sur certains devices Samsung/Xiaomi, suppression de la biométrie, etc.).
 *
 * Distinct du logout normal : le stockage chiffré est irrécupérable, il est donc
 * entièrement réinitialisé (config VPN comprise) et l'utilisateur est renvoyé vers
 * l'écran Setup. Non-dismissable pour éviter que l'app reste dans un état
 * indéterminé avec des clés nulles (CLAUDE.md §4).
 *
 * @param recoveryFailed true si une tentative de reset a échoué (stockage indisponible).
 * @param recoveryInProgress true pendant le reset — le bouton est désactivé.
 */
@Composable
private fun KeystoreCorruptionDialog(
    recoveryFailed: Boolean,
    recoveryInProgress: Boolean,
    onAcknowledge: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { /* non-dismissable */ },
        title = {
            androidx.compose.material3.Text(
                if (recoveryFailed) "Stockage sécurisé indisponible"
                else "Données sécurisées invalidées"
            )
        },
        text = {
            androidx.compose.material3.Text(
                if (recoveryFailed) {
                    "Le stockage sécurisé de l'appareil reste indisponible. " +
                        "Réessayez ; si le problème persiste, effacez les données de " +
                        "l'application dans les paramètres Android."
                } else {
                    "Les données sécurisées ont été invalidées par le système " +
                        "(redémarrage, mise à jour de sécurité ou changement biométrique). " +
                        "Vos données locales et la configuration VPN ont été réinitialisées — " +
                        "scannez à nouveau le QR de configuration."
                }
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = onAcknowledge,
                enabled = !recoveryInProgress,
            ) {
                androidx.compose.material3.Text(
                    when {
                        recoveryInProgress -> "Réinitialisation…"
                        recoveryFailed -> "Réessayer"
                        else -> "Reconfigurer"
                    }
                )
            }
        },
    )
}

// ── AppNavViewModel ────────────────────────────────────────────────────────────

/**
 * Root navigation ViewModel: reads the persisted auth state and admin flag from
 * [EncryptedDataStore] at startup, then follows the session events of [SessionManager].
 *
 * - [startDestination] is computed ONCE from the startup read and never changes afterwards:
 *   changing the NavHost start destination replaces the graph and resets the back stack.
 *   In-app navigation after a login is done explicitly by the Login / Totp success callbacks
 *   (→ Dashboard); after a logout, by the "forced logout" / "setup" effects of [AppNavGraph].
 * - [isLoggedIn] is a session STATE for banners, effects and guards (VPN banner, FCM deep
 *   link, forced-logout navigation) — never the source of the start destination. Tri-state:
 *   - `null`  — checking (datastore read in flight)
 *   - `true`  — session active (token at startup, or [SessionManager.sessionStartedEvents])
 *   - `false` — no session (no token at startup, [SessionManager.forcedLogoutEvents],
 *     Keystore corruption)
 * - [isAdmin] drives the « Flotte d'appareils (admin) » entry of the Settings hub and the Devices
 *   route guard (see [refreshIsAdmin]).
 */
@HiltViewModel
class AppNavViewModel @Inject constructor(
    private val getAuthContextUseCase: GetAuthContextUseCase,
    private val sessionManager: SessionManager,
    private val biometricLockManager: BiometricLockManager,
    private val wireGuardManager: WireGuardManager,
    private val systemVpnMonitor: SystemVpnMonitor,
    val biometricManager: BiometricManager,
    private val recoverFromKeystoreCorruptionUseCase: RecoverFromKeystoreCorruptionUseCase,
    private val logoutUseCase: LogoutUseCase,
    private val tokenHolder: TokenHolder,
) : ViewModel() {

    companion object {
        /**
         * Timeout pour le premier read EncryptedDataStore au démarrage.
         * Si dépassé, on suppose une corruption Keystore et on affiche le dialog
         * KeystoreCorruption plutôt que de laisser l'écran en blanc indéfiniment.
         */
        private const val AUTH_CONTEXT_TIMEOUT_MS = 3_000L
    }

    /** In-app WireGuard tunnel state — used by the Settings screen to show the
     *  tunnel managed by this process.  The banner uses [effectiveVpnState]
     *  instead so a system-level VPN (official WireGuard app, OpenVPN, ...)
     *  also counts as "connected". */
    val vpnState: StateFlow<VpnState> = wireGuardManager.state

    /**
     * VPN state as perceived by the app as a whole: Connected if EITHER the
     * in-app tunnel is up OR a system-level VPN tunnel is active.  This is
     * the flow the global [VpnStatusBanner] should observe.
     */
    val effectiveVpnState: StateFlow<VpnState> =
        combine(wireGuardManager.state, systemVpnMonitor.active) { inApp, sysActive ->
            computeEffectiveVpnState(inApp, sysActive)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            // Valeur initiale calculée avec le VPN système : l'ancienne (`wireGuardManager.state`
            // seul) valait `Disconnected` et affichait la bannière « VPN déconnecté » à chaque
            // démarrage à froid sous un tunnel de l'app WireGuard officielle, jusqu'à la 1re émission.
            initialValue = computeEffectiveVpnState(
                wireGuardManager.state.value,
                systemVpnMonitor.active.value || systemVpnMonitor.isActiveNow(),
            ),
        )

    /** Reconnects the VPN tunnel manually. */
    fun reconnectVpn() = wireGuardManager.reconnect()

    private val _isLoggedIn = MutableStateFlow<Boolean?>(null)

    /**
     * État de session réactif : valeur initiale lue au démarrage, puis `true` sur
     * [SessionManager.sessionStartedEvents] (login / 2FA réussis dans l'app) et `false` sur
     * [SessionManager.forcedLogoutEvents] / corruption Keystore. Sert aux bannières, effets et
     * guards — la destination de départ dépend uniquement de [startDestination].
     */
    val isLoggedIn: StateFlow<Boolean?> = _isLoggedIn.asStateFlow()

    /**
     * Destination de départ du NavHost, calculée UNE SEULE FOIS à partir du contexte lu au
     * démarrage (`null` tant que la lecture est en cours). Ne suit ni [isLoggedIn] ni
     * [isSetupCompleted] : un changement de startDestination remplacerait le graphe et viderait
     * la back stack. Survit aux changements de configuration (ViewModel à l'échelle Activity).
     */
    private val _startDestination = MutableStateFlow<String?>(null)
    val startDestination: StateFlow<String?> = _startDestination.asStateFlow()

    /**
     * Incrémenté à chaque logout forcé ([SessionManager.forcedLogoutEvents]).
     *
     * Clé de l'effet de navigation "→ Login" en plus de [isLoggedIn]. [isLoggedIn] suit les
     * événements de session (true → false → true …), mais un logout forcé peut encore arriver
     * alors qu'il vaut déjà `false` — deux logouts forcés consécutifs, ou un logout forcé reçu
     * entre le sessionStarted et la navigation Login → Dashboard suivi d'un second une fois sur
     * Dashboard : sans ce compteur, aucune transition de StateFlow et la navigation ne serait
     * pas relancée.
     */
    private val _forcedLogoutCount = MutableStateFlow(0)
    val forcedLogoutCount: StateFlow<Int> = _forcedLogoutCount.asStateFlow()

    /**
     * Escape hatch biométrique en cours (teardown de session → navigation Login → unlock).
     * Tant que true, un succès biométrique tardif est ignoré et un second appel est un no-op.
     */
    private val _escapeHatchInProgress = MutableStateFlow(false)

    /**
     * True quand le teardown de l'escape hatch est terminé et que l'overlay attend que l'écran
     * non authentifié (Login / Setup) soit affiché — transition de navigation terminée — pour
     * être levé. Observé par [AppNavGraph], qui appelle [onLoggedOutScreenShown].
     */
    private val _awaitingLoggedOutScreen = MutableStateFlow(false)
    val awaitingLoggedOutScreen: StateFlow<Boolean> = _awaitingLoggedOutScreen.asStateFlow()

    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    private val _showUpgradeRequired = MutableStateFlow(false)
    val showUpgradeRequired: StateFlow<Boolean> = _showUpgradeRequired.asStateFlow()

    /**
     * True quand le Keystore est corrompu (R1 fix).
     * L'UI affiche un dialog explicatif distinct du logout normal. Son bouton lance
     * [RecoverFromKeystoreCorruptionUseCase] (reset complet du stockage chiffré) puis
     * redirige vers l'écran Setup (rescan du QR de configuration).
     */
    private val _showKeystoreCorruption = MutableStateFlow(false)
    val showKeystoreCorruption: StateFlow<Boolean> = _showKeystoreCorruption.asStateFlow()

    /** True si la dernière tentative de reset a échoué — variante "stockage indisponible". */
    private val _keystoreRecoveryFailed = MutableStateFlow(false)
    val keystoreRecoveryFailed: StateFlow<Boolean> = _keystoreRecoveryFailed.asStateFlow()

    /** True pendant l'exécution du reset — évite les doubles clics. */
    private val _keystoreRecoveryInProgress = MutableStateFlow(false)
    val keystoreRecoveryInProgress: StateFlow<Boolean> = _keystoreRecoveryInProgress.asStateFlow()

    /**
     * Tri-state:
     * - `null`  — datastore read in flight
     * - `true`  — setup QR already scanned and VPN connected once → skip SetupScreen
     * - `false` — first launch or setup not completed → show SetupScreen
     */
    private val _isSetupCompleted = MutableStateFlow<Boolean?>(null)
    val isSetupCompleted: StateFlow<Boolean?> = _isSetupCompleted.asStateFlow()

    /** Biometric lock state — true when the inactivity overlay must be shown. */
    val biometricLocked: StateFlow<Boolean> = biometricLockManager.isLocked

    /** Deep link events from FCM — navigates to the given destination route name. */
    val deepLinkEvents = sessionManager.deepLinkEvents

    /** Clear le replay cache après consommation pour éviter re-navigation sur rotation. */
    fun clearDeepLink() = sessionManager.clearDeepLink()

    /** Called by the UI when biometric authentication succeeds. */
    fun onBiometricUnlocked() {
        if (_escapeHatchInProgress.value) {
            // Un prompt encore ouvert a pu réussir après le clic "Se reconnecter" : la session
            // est en cours de destruction, l'overlay ne doit pas découvrir le contenu authentifié.
            Timber.d("AppNavViewModel: biometric success ignored — escape hatch in progress")
            return
        }
        biometricLockManager.unlock()
    }

    /**
     * Called when the biometric key is invalidated (hardware failure, biometric removed)
     * or the user uses the escape hatch button after a long lock timeout.
     *
     * Ordre (l'overlay reste affiché pendant toute la séquence — aucun écran authentifié
     * n'est dessiné déverrouillé) :
     * 1. [LogoutUseCase] — même chemin que le logout utilisateur (SettingsViewModel) : logout
     *    API best-effort, TokenHolder / DataStore session / Room / CSRF / cookies vidés ;
     * 2. [TokenHolder.clear] (défense en profondeur, idempotent) — le guard du WS privé
     *    ([com.tradingplatform.app.data.websocket.PrivateWsClient]) bloque toute reconnexion ;
     * 3. [SessionManager.notifyForcedLogout] — navigation vers Login + fermeture du WS privé ;
     * 4. [awaitingLoggedOutScreen] = true — [AppNavGraph] attend que Login/Setup soit affiché
     *    (transition terminée) puis appelle [onLoggedOutScreenShown], qui seul déverrouille.
     */
    fun onBiometricEscapeHatch() {
        if (_escapeHatchInProgress.value) return
        _escapeHatchInProgress.value = true
        Timber.w("AppNavViewModel: biometric escape hatch — tearing down session before unlock")
        viewModelScope.launch {
            try {
                logoutUseCase()
                    .onFailure { Timber.w(it, "AppNavViewModel: escape hatch logout API failed — local state cleared anyway") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "AppNavViewModel: escape hatch logout threw — continuing teardown")
            }
            tokenHolder.clear()
            sessionManager.notifyForcedLogout()
            _awaitingLoggedOutScreen.value = true
        }
    }

    /**
     * Called by [AppNavGraph] once the unauthenticated start screen (Login / Setup) is the
     * resumed destination after an escape hatch — only then is the overlay lifted.
     * No-op outside of an escape hatch sequence.
     */
    fun onLoggedOutScreenShown() {
        if (!_awaitingLoggedOutScreen.value) return
        _awaitingLoggedOutScreen.value = false
        _escapeHatchInProgress.value = false
        biometricLockManager.unlock()
    }

    /**
     * Re-reads [isAdmin] from [EncryptedDataStore] via [GetAuthContextUseCase].
     *
     * Must be called after a successful login (direct or via 2FA) — i.e. once the login use
     * case has returned — so that the Settings admin entry (Devices fleet) and the admin guard
     * in the NavHost reflect the newly persisted flag without requiring an app restart.
     *
     * Deliberately NOT done on [SessionManager.sessionStartedEvents]: [AuthRepositoryImpl]
     * emits that event right after populating [TokenHolder] and BEFORE writing `IS_ADMIN` to
     * the DataStore, so a read triggered by the event would return the previous (cleared) value.
     *
     * [isAdmin] is deliberately not reset on logout either: the admin entry / Devices routes are
     * not reachable from the logged-out screens, and flipping it while the Devices screen is
     * still composed would race the Devices guard navigation (→ Dashboard) with the forced
     * logout navigation (→ Login). The next login overwrites it through this method.
     */
    fun refreshIsAdmin() {
        viewModelScope.launch {
            val context = getAuthContextUseCase()
            _isAdmin.value = context.isAdmin
            Timber.d("AppNavViewModel: refreshIsAdmin → isAdmin=${context.isAdmin}")
        }
    }

    init {
        viewModelScope.launch {
            // Si le DataStore chiffré est illisible (Keystore invalidé après reboot
            // sur certains devices), les reads suspendent indéfiniment. Timeout 3s →
            // fallback KeystoreCorruption pour débloquer la navigation plutôt que de
            // laisser un écran blanc éternel (retour = null).
            val context = withTimeoutOrNull(AUTH_CONTEXT_TIMEOUT_MS) {
                getAuthContextUseCase()
            }
            if (context == null) {
                Timber.e("AppNavViewModel: auth context read timed out (${AUTH_CONTEXT_TIMEOUT_MS}ms) — treating as Keystore corruption")
                _showKeystoreCorruption.value = true
                _isAdmin.value = false
                _isLoggedIn.value = false
                _isSetupCompleted.value = true  // laisser passer Setup pour arriver sur Login
                _startDestination.value = startDestinationFor(setupCompleted = true, loggedIn = false)
                return@launch
            }
            _isAdmin.value = context.isAdmin
            _isLoggedIn.value = context.isLoggedIn
            _isSetupCompleted.value = context.setupCompleted
            _startDestination.value = startDestinationFor(
                setupCompleted = context.setupCompleted,
                loggedIn = context.isLoggedIn,
            )
            Timber.d(
                "AppNavViewModel: isLoggedIn=${context.isLoggedIn}, " +
                    "isAdmin=${context.isAdmin}, setupCompleted=${context.setupCompleted}"
            )
        }
        viewModelScope.launch {
            // Login / vérification 2FA réussis pendant la session. Seul l'état change ici :
            // la navigation Login/Totp → Dashboard est faite par leurs callbacks de succès
            // (après la fin du use case), qui rafraîchissent aussi isAdmin (refreshIsAdmin).
            sessionManager.sessionStartedEvents.collect {
                Timber.d("AppNavViewModel: session started — isLoggedIn=true")
                _isLoggedIn.value = true
            }
        }
        viewModelScope.launch {
            sessionManager.forcedLogoutEvents.collect {
                Timber.w("AppNavViewModel: forced logout received — redirecting to Login")
                _isLoggedIn.value = false
                _forcedLogoutCount.update { it + 1 }
            }
        }
        viewModelScope.launch {
            sessionManager.upgradeRequiredEvents.collect {
                Timber.w("AppNavViewModel: HTTP 426 received — showing upgrade required dialog")
                _showUpgradeRequired.value = true
            }
        }
        viewModelScope.launch {
            sessionManager.keystoreCorruptionEvents.collect {
                Timber.e("AppNavViewModel: Keystore corruption detected — showing corruption dialog")
                _showKeystoreCorruption.value = true
                _isLoggedIn.value = false
            }
        }
    }

    /**
     * Called by the UI when the user acknowledges the Keystore corruption dialog.
     *
     * Lance le reset complet ([RecoverFromKeystoreCorruptionUseCase]). En cas de succès,
     * le dialog est fermé et l'app repart de l'écran Setup (isSetupCompleted=false) :
     * la config VPN a été perdue avec le reste du stockage. En cas d'échec, le dialog
     * reste affiché dans sa variante "stockage indisponible" pour permettre un nouvel essai.
     */
    fun onKeystoreCorruptionAcknowledged() {
        if (_keystoreRecoveryInProgress.value) return
        _keystoreRecoveryInProgress.value = true
        viewModelScope.launch {
            val recovered = try {
                recoverFromKeystoreCorruptionUseCase()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "AppNavViewModel: keystore recovery threw")
                false
            }
            _keystoreRecoveryInProgress.value = false
            if (recovered) {
                _keystoreRecoveryFailed.value = false
                _isAdmin.value = false
                // isSetupCompleted avant isLoggedIn : l'effet de navigation "Setup" doit
                // prévaloir sur l'effet "isLoggedIn == false → Login".
                _isSetupCompleted.value = false
                _isLoggedIn.value = false
                _showKeystoreCorruption.value = false
            } else {
                _keystoreRecoveryFailed.value = true
                _showKeystoreCorruption.value = true
            }
        }
    }

    private fun startDestinationFor(setupCompleted: Boolean, loggedIn: Boolean): String = when {
        !setupCompleted -> Screen.Setup.route
        loggedIn -> Screen.Dashboard.route
        else -> Screen.Login.route
    }

    /** Called by the UI when the Setup flow completes (VPN configured). */
    fun onSetupCompleted() {
        _isSetupCompleted.value = true
    }

    /**
     * Ferme tous les dialogs globaux avant une navigation forcée vers Login.
     * Évite que les AlertDialog (upgradeRequired / keystoreCorruption) restent
     * empilés au-dessus de LoginScreen après un logout forcé.
     */
    fun dismissAllDialogs() {
        _showUpgradeRequired.value = false
        _showKeystoreCorruption.value = false
    }
}

// ── AppNavGraph ────────────────────────────────────────────────────────────────

private const val PAIRING_GRAPH_ROUTE = "pairing_graph/{source}"
private const val PAIRING_SOURCE_DEVICES = "devices"
private const val PAIRING_SOURCE_MY_DEVICES = "my-devices"

private fun pairingGraphRoute(source: String) = "pairing_graph/$source"

/** Destinations non authentifiées — cibles d'un logout (Setup si la config VPN a été perdue). */
private val LOGGED_OUT_ROUTES = setOf(Screen.Login.route, Screen.Setup.route)

/** Destinations où un logout forcé ne doit pas relancer la navigation (déjà hors session). */
private val NO_SESSION_ROUTES = LOGGED_OUT_ROUTES + Screen.Totp.route

/**
 * Suspend jusqu'à ce que la destination courante soit un écran non authentifié
 * ([LOGGED_OUT_ROUTES]) à l'état RESUMED. Avec les transitions NavHost, l'entrée entrante reste
 * STARTED pendant l'animation et ne passe RESUMED qu'une fois la transition terminée — l'écran
 * authentifié sortant n'est alors plus dessiné.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun awaitLoggedOutScreenResumed(navController: NavHostController) {
    navController.currentBackStackEntryFlow
        .flatMapLatest { entry ->
            if (entry.destination.route in LOGGED_OUT_ROUTES) {
                entry.lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.RESUMED) }
            } else {
                flowOf(false)
            }
        }
        .first { it }
}

private fun pairingReturnRoute(source: String?) = when (source) {
    PAIRING_SOURCE_MY_DEVICES -> Screen.MyDevices.route
    else -> Screen.Devices.route
}

/**
 * Root navigation graph for the application.
 *
 * The start destination comes from [AppNavViewModel.startDestination], computed once from the
 * startup auth state. While that state is being determined (null), nothing is rendered to
 * avoid a flash. Later session changes never touch the start destination: they are explicit
 * navigations (Login/Totp success → Dashboard, forced logout → Login, setup lost → Setup),
 * and [AppNavViewModel.isLoggedIn] only feeds banners / effects / guards.
 *
 * FCM deep link: collects [AppNavViewModel.deepLinkEvents] (fed from the "navigate_to"
 * Activity extra) and navigates to [Screen.Alerts] once a session is active and an
 * authenticated screen is shown.
 *
 * Pairing flow: the four pairing screens share a single [PairingViewModel] instance
 * scoped to the nested "pairing_graph" navigation graph via
 * `hiltViewModel(navController.getBackStackEntry(PAIRING_GRAPH_ROUTE))`.
 *
 * Admin guard: navigating to [Screen.Devices] when [AppNavViewModel.isAdmin] is false
 * redirects to [Screen.Dashboard].
 */
@Composable
fun AppNavGraph(
    modifier: Modifier = Modifier,
    appNavViewModel: AppNavViewModel = hiltViewModel(),
) {
    val isLoggedIn by appNavViewModel.isLoggedIn.collectAsStateWithLifecycle()
    val isAdmin by appNavViewModel.isAdmin.collectAsStateWithLifecycle()
    val isSetupCompleted by appNavViewModel.isSetupCompleted.collectAsStateWithLifecycle()
    // Banner must reflect the "any VPN active" view (in-app OR system-level).
    val vpnState by appNavViewModel.effectiveVpnState.collectAsStateWithLifecycle()
    val showUpgradeRequired by appNavViewModel.showUpgradeRequired.collectAsStateWithLifecycle()
    val showKeystoreCorruption by appNavViewModel.showKeystoreCorruption.collectAsStateWithLifecycle()
    val keystoreRecoveryFailed by appNavViewModel.keystoreRecoveryFailed.collectAsStateWithLifecycle()
    val keystoreRecoveryInProgress by appNavViewModel.keystoreRecoveryInProgress.collectAsStateWithLifecycle()
    val biometricLocked by appNavViewModel.biometricLocked.collectAsStateWithLifecycle()
    val forcedLogoutCount by appNavViewModel.forcedLogoutCount.collectAsStateWithLifecycle()
    val awaitingLoggedOutScreen by appNavViewModel.awaitingLoggedOutScreen.collectAsStateWithLifecycle()
    val startDestinationOrNull by appNavViewModel.startDestination.collectAsStateWithLifecycle()

    // Wait until all datastore checks complete before rendering anything.
    // isLoggedIn, isSetupCompleted and startDestination are set together in the same init
    // coroutine (all null initially) and never go back to null.
    // Biometric lock: BiometricLockManager.isLocked starts at `true` (fail-closed until the
    // cold-start restore/unlock in TradingApplication). Once this gate opens, the overlay below
    // is composed in the same Box as the Scaffold, above it (zIndex), and AnimatedVisibility
    // shows it without a fade-in on its first composition → no authenticated frame is exposed.
    val loggedIn = isLoggedIn ?: return
    if (isSetupCompleted == null) return
    // Figé au démarrage (AppNavViewModel.startDestination) : un startDestination qui suivrait
    // isLoggedIn / isSetupCompleted remplacerait le graphe du NavHost et viderait la back stack
    // à chaque login / logout. Les transitions de session sont des navigations explicites :
    // callbacks de succès Login/Totp → Dashboard, effets "logout forcé" → Login et "setup" → Setup.
    val startDestination = startDestinationOrNull ?: return

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // BottomNavBar : uniquement sur les routes racines des 4 onglets (Accueil, Portefeuille —
    // Positions / Ordres / Historique —, Marchés, Alertes). Réglages (+ sous-écrans), flotte
    // Devices, détail de position, Performance, pairing… sont des écrans poussés sans barre.
    val showBottomBar = showsBottomBar(currentRoute)

    // Icône Réglages des TopAppBar racines. launchSingleTop : un double tap n'empile pas deux fois.
    val openSettings: () -> Unit = {
        navController.navigate(Screen.Settings.route) {
            launchSingleTop = true
        }
    }

    // Forced logout — triggered by SessionManager when TokenAuthenticator invalidates the session.
    // When isLoggedIn transitions to false after being true (expired token, forced logout),
    // navigate to Login and clear the entire backstack so the user can re-authenticate.
    // Sans cet effet, rien ne ramènerait à Login : startDestination est figé au démarrage.
    // Les dialogs globaux (upgradeRequired / keystoreCorruption) sont fermés explicitement
    // sauf si c'est la corruption Keystore qui a déclenché le logout (dialog encore nécessaire).
    //
    // Si le setup n'est pas (ou plus) complété — premier lancement ou reset du stockage
    // chiffré après corruption Keystore — c'est l'effet "Setup" ci-dessous qui gagne :
    // sans config VPN, Login est inutilisable.
    //
    // Clé forcedLogoutCount : isLoggedIn suit les événements de session (logout → login →
    // logout = false → true → false), mais un logout forcé peut encore arriver alors qu'il vaut
    // déjà false (deux logouts consécutifs, logout reçu entre sessionStarted et la navigation
    // Login → Dashboard) — sans ce compteur, cet effet ne serait alors pas relancé. Déjà sur un
    // écran hors session (Login / Totp / Setup) → no-op, pour ne pas recréer LoginScreen à
    // chaque 401 synthétique émis sans token.
    LaunchedEffect(isLoggedIn, forcedLogoutCount) {
        if (isLoggedIn == false && isSetupCompleted != false &&
            navController.currentDestination?.route !in NO_SESSION_ROUTES
        ) {
            if (!showKeystoreCorruption) {
                appNavViewModel.dismissAllDialogs()
            }
            navController.navigate(Screen.Login.route) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // Escape hatch biométrique (PR 1.7) : la session a déjà été détruite et le logout forcé
    // émis (→ effet ci-dessus). L'overlay reste verrouillé jusqu'à ce que Login/Setup soit la
    // destination RESUMED (transition terminée) — aucun écran authentifié n'est dessiné
    // déverrouillé. Seul ce chemin déverrouille après un escape hatch.
    LaunchedEffect(awaitingLoggedOutScreen) {
        if (awaitingLoggedOutScreen) {
            awaitLoggedOutScreenResumed(navController)
            appNavViewModel.onLoggedOutScreenShown()
        }
    }

    // Setup non complété (ou plus complété après reset du stockage chiffré suite à une
    // corruption Keystore — audit #17) : toute la config VPN a été perdue, on renvoie
    // l'utilisateur vers SetupScreen en vidant la back stack. No-op si déjà sur Setup
    // (premier lancement : c'est la startDestination).
    LaunchedEffect(isSetupCompleted) {
        if (isSetupCompleted == false &&
            navController.currentDestination?.route != Screen.Setup.route
        ) {
            navController.navigate(Screen.Setup.route) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // FCM deep link — collect events from SessionManager so it works for both
    // onCreate (cold start from notification) and onNewIntent (app already in foreground).
    // [SessionManager.deepLinkEvents] utilise replay=1 : un event émis avant la composition
    // (cold start depuis notification) est re-joué au premier subscribe. On attend que
    // le NavController ait chargé sa destination de départ avant de consommer, puis on
    // clear le replay cache après consommation pour éviter une re-navigation sur rotation.
    //
    // Clé loggedIn (réactif, PR 6.1) : hors session, rien n'est consommé — l'event reste dans
    // le replay cache et est re-joué quand l'effet redémarre après un login fait dans l'app.
    // isLoggedIn passe à true dès le sessionStarted, AVANT que Login/Totp n'aient navigué vers
    // Dashboard (le use case charge encore le portfolio) : on attend donc qu'une destination
    // authentifiée soit affichée, sinon Alerts serait empilé au-dessus de Login puis effacé par
    // la navigation Login → Dashboard. Un logout pendant l'attente annule l'effet (clé).
    LaunchedEffect(loggedIn) {
        if (!loggedIn) return@LaunchedEffect
        appNavViewModel.deepLinkEvents.collect { destination ->
            if (destination == "alerts") {
                // Back stack initialisée (cold start depuis notification) ET hors écrans
                // non authentifiés (Login / Totp / Setup).
                navController.currentBackStackEntryFlow
                    .first { it.destination.route !in NO_SESSION_ROUTES }
                navController.navigate(Screen.Alerts.route) {
                    popUpTo(Screen.Dashboard.route) { saveState = false }
                    launchSingleTop = true
                }
                appNavViewModel.clearDeepLink()
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
    Scaffold(
        // While locked, hide the content under the overlay from accessibility services
        // (TalkBack would otherwise still read P&L / positions behind the opaque overlay).
        modifier = Modifier
            .fillMaxSize()
            .then(if (biometricLocked) Modifier.clearAndSetSemantics {} else Modifier),
        // Pas d'inset haut ici : chaque écran a son propre Scaffold/TopAppBar (ou systemBarsPadding)
        // qui réserve déjà la barre d'état, et la bannière VPN la réserve elle-même quand elle est
        // visible (et la consomme). Sans ça, l'inset était compté deux fois (~130 px perdus en haut).
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
        ),
        bottomBar = {
            if (showBottomBar) {
                BottomNavBar(
                    navController = navController,
                )
            }
        },
    ) { innerPadding ->
        // HTTP 426 — dialog non-dismissable affiché par-dessus tout le contenu
        if (showUpgradeRequired) {
            UpgradeRequiredDialog()
        }

        // Keystore corruption (R1 fix) — dialog explicatif distinct du logout normal.
        // Affiché quand EncryptedDataStore est illisible suite à une invalidation Keystore
        // (reboot Samsung/Xiaomi, suppression biométrie, reset device).
        if (showKeystoreCorruption) {
            KeystoreCorruptionDialog(
                recoveryFailed = keystoreRecoveryFailed,
                recoveryInProgress = keystoreRecoveryInProgress,
                onAcknowledge = { appNavViewModel.onKeystoreCorruptionAcknowledged() },
            )
        }

        Column(modifier = Modifier.padding(innerPadding)) {
            // Global VPN disconnect banner
            val isVpnDisconnected = loggedIn && (vpnState is VpnState.Disconnected || vpnState is VpnState.ConsentRequired)
            val isVpnConnecting = loggedIn && vpnState is VpnState.Connecting
            VpnStatusBanner(
                isDisconnected = isVpnDisconnected,
                isConnecting = isVpnConnecting,
                onReconnect = { appNavViewModel.reconnectVpn() }
            )

        // La bannière (frère du NavHost, pas son parent) occupe la zone de la barre d'état : on
        // consomme l'inset haut côté NavHost, sinon la TopAppBar de l'écran le réserve une 2e fois.
        val topBarInsets = TopAppBarDefaults.windowInsets.only(WindowInsetsSides.Top)
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isVpnDisconnected || isVpnConnecting) {
                        Modifier.consumeWindowInsets(topBarInsets)
                    } else {
                        Modifier
                    },
                ),
            enterTransition = { NavTransitions.enterTransition(this) },
            exitTransition = { NavTransitions.exitTransition(this) },
            popEnterTransition = { NavTransitions.popEnterTransition(this) },
            popExitTransition = { NavTransitions.popExitTransition(this) },
        ) {

            // ── Onboarding setup (first launch only) ─────────────────────────

            composable(Screen.Setup.route) {
                SetupScreen(
                    onSetupComplete = {
                        // Resynchronise le flag : sans ça, un second reset (corruption
                        // Keystore) dans la même session ne ferait pas transiter
                        // isSetupCompleted false → false et l'effet "Setup" ne se relancerait pas.
                        appNavViewModel.onSetupCompleted()
                        navController.navigate(Screen.Login.route) {
                            popUpTo(Screen.Setup.route) { inclusive = true }
                        }
                    },
                )
            }

            // ── Auth ──────────────────────────────────────────────────────────

            composable(Screen.Login.route) {
                LoginScreen(
                    onNavigateToDashboard = {
                        // Navigation explicite post-login : startDestination est figé au
                        // démarrage, isLoggedIn (déjà true via sessionStarted) n'est qu'un état.
                        // Refresh isAdmin before navigating — LoginUseCase has just written the
                        // real value to EncryptedDataStore (after the sessionStarted event);
                        // the startup read in init{} returned false (no token yet). Without this
                        // refresh the Settings « Flotte d'appareils » entry stays hidden for
                        // admins who log in during this app session.
                        appNavViewModel.refreshIsAdmin()
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    },
                    onNavigateToTotp = {
                        // sessionToken already stored in SessionManager by LoginViewModel
                        navController.navigate(Screen.Totp.route)
                    },
                )
            }

            composable(route = Screen.Totp.route) {
                TotpScreen(
                    onNavigateToDashboard = {
                        // Same refresh as the direct-login path — 2FA also writes is_admin.
                        appNavViewModel.refreshIsAdmin()
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                )
            }

            // ── Main tabs ─────────────────────────────────────────────────────

            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    onNavigateToPerformance = {
                        navController.navigate(Screen.Performance.route)
                    },
                    // Même navigation que l'onglet Alertes de la barre (popUpTo Accueil,
                    // saveState/restoreState, launchSingleTop).
                    onNavigateToAlerts = {
                        navController.navigateToTab(Screen.Alerts.route)
                    },
                    onOpenSettings = openSettings,
                )
            }

            composable(Screen.Performance.route) {
                PerformanceScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            // ── Onglet Portefeuille : Positions (racine) / Ordres / Historique ──
            // Les trois écrans partagent la rangée de segments ; choisir un segment remplace la
            // section courante sans empiler la back stack (navigateToPortfolioSegment). Retour
            // depuis Ordres / Historique → Positions, puis Positions → Accueil.

            composable(Screen.TransactionHistory.route) {
                TransactionHistoryScreen(
                    onSelectSegment = { segment -> navController.navigateToPortfolioSegment(segment) },
                    onOpenSettings = openSettings,
                )
            }

            composable(Screen.Orders.route) {
                OrdersScreen(
                    onSelectSegment = { segment -> navController.navigateToPortfolioSegment(segment) },
                    onOpenSettings = openSettings,
                )
            }

            composable(Screen.MarketData.route) {
                MarketDataScreen(
                    onOpenSettings = openSettings,
                )
            }

            composable(Screen.Positions.route) {
                PositionsScreen(
                    onNavigateToDetail = { positionId ->
                        navController.navigate(Screen.PositionDetail.createRoute(positionId))
                    },
                    onSelectSegment = { segment -> navController.navigateToPortfolioSegment(segment) },
                    onOpenSettings = openSettings,
                )
            }

            composable(
                route = Screen.PositionDetail.route,
                arguments = listOf(
                    navArgument("positionId") { type = NavType.IntType },
                ),
            ) { backStackEntry ->
                val positionId = backStackEntry.arguments?.getInt("positionId") ?: -1
                PositionDetailScreen(
                    positionId = positionId,
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            composable(Screen.Alerts.route) {
                AlertListScreen(
                    onOpenSettings = openSettings,
                )
            }

            // ── Devices — flotte (admin only) ─────────────────────────────────
            // Plus d'onglet : atteinte depuis Réglages → « Flotte d'appareils (admin) ».

            composable(Screen.Devices.route) {
                // Admin guard — redirect to Dashboard if not admin
                if (!isAdmin) {
                    LaunchedEffect(Unit) {
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(Screen.Devices.route) { inclusive = true }
                        }
                    }
                    return@composable
                }
                DeviceListScreen(
                    onNavigateToDetail = { deviceId ->
                        navController.navigate(Screen.DeviceDetail.createRoute(deviceId))
                    },
                    onNavigateToPairing = {
                        navController.navigate(pairingGraphRoute(PAIRING_SOURCE_DEVICES)) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            composable(
                route = Screen.DeviceDetail.route,
                arguments = listOf(
                    navArgument("deviceId") { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val deviceId = backStackEntry.arguments?.getString("deviceId") ?: ""
                EdgeDeviceDashboardScreen(
                    deviceId = deviceId,
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            // ── Pairing nested graph ──────────────────────────────────────────
            // All four screens share a single PairingViewModel scoped to this graph.

            navigation(
                startDestination = Screen.ScanVpsQr.route,
                route = PAIRING_GRAPH_ROUTE,
                arguments = listOf(
                    navArgument("source") { type = NavType.StringType },
                ),
            ) {
                composable(Screen.ScanVpsQr.route) { backStackEntry ->
                    val pairingEntry = remember(backStackEntry) {
                        navController.getBackStackEntry(PAIRING_GRAPH_ROUTE)
                    }
                    val pairingViewModel: PairingViewModel = hiltViewModel(pairingEntry)
                    val source = pairingEntry.arguments?.getString("source")
                    val returnRoute = pairingReturnRoute(source)
                    ScanVpsQrScreen(
                        onNavigateToScanDevice = {
                            navController.navigate(Screen.ScanDeviceQr.route)
                        },
                        onNavigateToProgress = {
                            navController.navigate(Screen.PairingProgress.route) {
                                popUpTo(Screen.ScanVpsQr.route) { inclusive = false }
                            }
                        },
                        onBack = {
                            navController.popBackStack(
                                route = returnRoute,
                                inclusive = false,
                            )
                        },
                        viewModel = pairingViewModel,
                    )
                }

                composable(Screen.ScanDeviceQr.route) { backStackEntry ->
                    val pairingEntry = remember(backStackEntry) {
                        navController.getBackStackEntry(PAIRING_GRAPH_ROUTE)
                    }
                    val pairingViewModel: PairingViewModel = hiltViewModel(pairingEntry)
                    ScanDeviceQrScreen(
                        onNavigateToProgress = {
                            navController.navigate(Screen.PairingProgress.route) {
                                popUpTo(Screen.ScanVpsQr.route) { inclusive = false }
                            }
                        },
                        onBack = { navController.popBackStack() },
                        viewModel = pairingViewModel,
                    )
                }

                composable(Screen.PairingProgress.route) { backStackEntry ->
                    val pairingEntry = remember(backStackEntry) {
                        navController.getBackStackEntry(PAIRING_GRAPH_ROUTE)
                    }
                    val pairingViewModel: PairingViewModel = hiltViewModel(pairingEntry)
                    val source = pairingEntry.arguments?.getString("source")
                    val returnRoute = pairingReturnRoute(source)
                    PairingProgressScreen(
                        onPairingComplete = {
                            navController.navigate(Screen.PairingDone.route) {
                                popUpTo(Screen.PairingProgress.route) { inclusive = true }
                            }
                        },
                        onCancel = {
                            navController.popBackStack(
                                route = returnRoute,
                                inclusive = false,
                            )
                        },
                        viewModel = pairingViewModel,
                    )
                }

                composable(Screen.PairingDone.route) { backStackEntry ->
                    val pairingEntry = remember(backStackEntry) {
                        navController.getBackStackEntry(PAIRING_GRAPH_ROUTE)
                    }
                    val pairingViewModel: PairingViewModel = hiltViewModel(pairingEntry)
                    val source = pairingEntry.arguments?.getString("source")
                    val returnRoute = pairingReturnRoute(source)
                    val step by pairingViewModel.step.collectAsStateWithLifecycle()
                    PairingDoneScreen(
                        step = step,
                        onRetry = {
                            pairingViewModel.reset()
                            navController.navigate(Screen.ScanVpsQr.route) {
                                popUpTo(Screen.ScanVpsQr.route) { inclusive = true }
                            }
                        },
                        onFinish = {
                            navController.navigate(returnRoute) {
                                popUpTo(PAIRING_GRAPH_ROUTE) { inclusive = true }
                            }
                        },
                        viewModel = pairingViewModel,
                    )
                }
            }

            // ── Settings ──────────────────────────────────────────────────────

            composable(Screen.Settings.route) {
                SettingsScreen(
                    isAdmin = isAdmin,
                    onNavigateToDevices = {
                        navController.navigate(Screen.Devices.route) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToVpn = {
                        navController.navigate(Screen.VpnSettings.route)
                    },
                    onNavigateToMyDevices = {
                        navController.navigate(Screen.MyDevices.route)
                    },
                    onNavigateToSecurity = {
                        navController.navigate(Screen.SecuritySettings.route)
                    },
                    onNavigateToProfile = {
                        navController.navigate(Screen.Profile.route)
                    },
                )
            }

            composable(Screen.Profile.route) {
                ProfileScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            composable(Screen.MyDevices.route) {
                MyDevicesScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToPairing = {
                        navController.navigate(pairingGraphRoute(PAIRING_SOURCE_MY_DEVICES))
                    },
                )
            }

            composable(Screen.VpnSettings.route) {
                VpnSettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            composable(Screen.SecuritySettings.route) {
                SecuritySettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }
        }
        } // Column
    } // Scaffold

    // Biometric lock overlay — shown on top of all content whenever BiometricLockManager.isLocked
    // is true (cold start with a session, inactivity timeout, process restored while locked).
    // BiometricLockManager owns the inactivity timer; MainActivity only forwards touches.
    // The overlay is opaque, consumes touches and back presses, and only unlocks on a
    // successful BiometricPrompt (fail-closed) — CLAUDE.md §4.
    // Keystore corrompu (dialog affiché, typiquement au démarrage à froid où le verrou D7 est
    // actif) : l'overlay reste affiché et opaque mais ne lance pas le prompt biométrique —
    // le dialog de récupération est la seule action ; RecoverFromKeystoreCorruptionUseCase
    // déverrouille après un reset réussi.
    BiometricLockOverlay(
        isLocked = biometricLocked,
        onAuthSuccess = { appNavViewModel.onBiometricUnlocked() },
        onKeyInvalidated = { appNavViewModel.onBiometricEscapeHatch() },
        modifier = Modifier
            .fillMaxSize()
            .zIndex(Float.MAX_VALUE),
        biometricManager = appNavViewModel.biometricManager,
        authEnabled = !showKeystoreCorruption,
    )
    } // Box
}
