package com.tradingplatform.app.data.session

import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bus d'événements de session partagé entre la couche OkHttp et la couche UI.
 *
 * [forcedLogoutEvents] est collecté par [AppNavViewModel] pour déclencher la
 * navigation vers LoginScreen quand [TokenAuthenticator] invalide la session.
 *
 * [upgradeRequiredEvents] est collecté par [AppNavViewModel] pour afficher un
 * dialog bloquant "Mise à jour requise" quand le VPS retourne HTTP 426.
 *
 * [deepLinkEvents] est collecté par [AppNavViewModel] pour déclencher la navigation
 * vers une destination depuis un deep link FCM (onCreate ET onNewIntent).
 *
 * SharedFlow (replay=0) : un seul consommateur suffit (AppNavViewModel).
 * Les émetteurs n'attendent pas — tryEmit.
 */
@Singleton
class SessionManager @Inject constructor() {
    private val _forcedLogoutEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val forcedLogoutEvents: SharedFlow<Unit> = _forcedLogoutEvents.asSharedFlow()

    fun notifyForcedLogout() {
        FirebaseCrashlytics.getInstance().log("SessionManager: forced logout")
        _forcedLogoutEvents.tryEmit(Unit)
    }

    private val _upgradeRequiredEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val upgradeRequiredEvents: SharedFlow<Unit> = _upgradeRequiredEvents.asSharedFlow()

    fun notifyUpgradeRequired() {
        _upgradeRequiredEvents.tryEmit(Unit)
    }

    /**
     * Corruption du Keystore detectee (R1 fix).
     *
     * Distinct de [forcedLogoutEvents] : le consommateur (AppNavViewModel) affiche un
     * message explicite a l'utilisateur ("Donnees de session corrompues — reconnexion
     * necessaire") au lieu d'un logout silencieux. L'UI peut aussi proposer un clear
     * des donnees chiffrees avant le re-login.
     */
    private val _keystoreCorruptionEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val keystoreCorruptionEvents: SharedFlow<Unit> = _keystoreCorruptionEvents.asSharedFlow()

    fun notifyKeystoreCorruption() {
        FirebaseCrashlytics.getInstance().apply {
            setCustomKey("keystore_corruption_detected", true)
            log("SessionManager: Keystore corruption detected")
        }
        _keystoreCorruptionEvents.tryEmit(Unit)
    }

    /**
     * Deep link events — replay=1 pour que les events émis par [MainActivity.handleDeepLinkIntent]
     * en `onCreate` (avant que `setContent` ne démarre les collecteurs) soient délivrés au
     * premier subscribe (cold start depuis notification FCM).
     */
    private val _deepLinkEvents = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 1)
    val deepLinkEvents: SharedFlow<String> = _deepLinkEvents.asSharedFlow()

    fun notifyDeepLink(destination: String) {
        _deepLinkEvents.tryEmit(destination)
    }

    /**
     * Efface la dernière valeur replayée du deep link flow après consommation.
     * Empêche la re-navigation vers la même destination si le ViewModel est recréé
     * (rotation d'écran, process kill + restore).
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun clearDeepLink() {
        _deepLinkEvents.resetReplayCache()
    }

    private val _pendingTotpToken = AtomicReference<String?>(null)

    /** Exposé en lecture seule pour les cas où l'appelant doit inspecter sans consommer. */
    val pendingTotpToken: String? get() = _pendingTotpToken.get()

    fun storePendingTotpToken(token: String) {
        _pendingTotpToken.set(token)
    }

    /**
     * Lit et efface le token TOTP en attente en une seule opération atomique.
     * Garantit qu'un token ne peut être consommé qu'une seule fois, même sous concurrence.
     */
    fun consumePendingTotpToken(): String? = _pendingTotpToken.getAndSet(null)
}
