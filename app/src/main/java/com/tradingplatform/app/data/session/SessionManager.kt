package com.tradingplatform.app.data.session

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
        _forcedLogoutEvents.tryEmit(Unit)
    }

    private val _upgradeRequiredEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val upgradeRequiredEvents: SharedFlow<Unit> = _upgradeRequiredEvents.asSharedFlow()

    fun notifyUpgradeRequired() {
        _upgradeRequiredEvents.tryEmit(Unit)
    }

    private val _deepLinkEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val deepLinkEvents: SharedFlow<String> = _deepLinkEvents.asSharedFlow()

    fun notifyDeepLink(destination: String) {
        _deepLinkEvents.tryEmit(destination)
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
