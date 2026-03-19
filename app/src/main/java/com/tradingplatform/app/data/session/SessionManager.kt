package com.tradingplatform.app.data.session

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bus d'événements de session partagé entre la couche OkHttp et la couche UI.
 *
 * [forcedLogoutEvents] est collecté par [AppNavViewModel] pour déclencher la
 * navigation vers LoginScreen quand [TokenAuthenticator] invalide la session.
 *
 * SharedFlow (replay=0) : un seul consommateur suffit (AppNavViewModel).
 * L'émetteur (TokenAuthenticator) n'attend pas — tryEmit.
 */
@Singleton
class SessionManager @Inject constructor() {
    private val _forcedLogoutEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val forcedLogoutEvents: SharedFlow<Unit> = _forcedLogoutEvents.asSharedFlow()

    fun notifyForcedLogout() {
        _forcedLogoutEvents.tryEmit(Unit)
    }
}
