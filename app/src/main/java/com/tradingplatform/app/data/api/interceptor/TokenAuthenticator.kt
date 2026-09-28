package com.tradingplatform.app.data.api.interceptor

import androidx.annotation.VisibleForTesting
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.tradingplatform.app.data.api.AuthApi
import com.tradingplatform.app.data.api.AuthPaths
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.data.local.db.AppDatabase
import com.tradingplatform.app.data.session.SessionManager
import com.tradingplatform.app.data.session.TokenHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Refresh transparent du JWT access token sur réception d'un 401 (AUTH_1002).
 *
 * Ordre des décisions dans [authenticate] :
 * 1. `priorResponse` déjà en 401 → abandon : un seul retry par requête d'origine (une
 *    ressource qui reste en 401 avec un token frais ne déclenche pas de 2e refresh).
 * 2. 401 sur `/v1/auth/refresh` → refresh token invalide → logout forcé.
 * 3. 401 sur un autre chemin [AuthPaths.PUBLIC] (login, 2fa/verify, csrf) → échec métier
 *    (mauvais mot de passe / code TOTP) : ni refresh ni logout.
 * 4. Bearer périmé : le token envoyé diffère de [TokenHolder.accessToken] (une autre
 *    requête a déjà rafraîchi) → retry immédiat avec le token courant, sans refresh.
 * 5. Sinon [refreshOnce] : un seul refresh en vol, partagé par tous les appelants.
 *
 * Concurrence : le [Mutex] ne protège que le champ [refreshDeferred] — il n'est jamais tenu
 * pendant un appel réseau. Le refresh tourne dans [applicationScope] ; chaque appelant attend
 * le [Deferred] partagé sous [withTimeoutOrNull], qui n'annule que l'attente, jamais le
 * refresh : celui-ci termine (borné par les timeouts 5 s du client refresh) et alimente
 * [TokenHolder], si bien que la requête suivante prend le chemin rapide « bearer périmé ».
 *
 * [authApi] est construit sur le client `@Named("refresh")` (NetworkModule) : Dispatcher
 * dédié, sans CsrfInterceptor / AuthInterceptor / Authenticator. Pas de cycle Hilt (plus
 * besoin de dagger.Lazy), pas de famine du pool du client principal, pas de refresh récursif.
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val applicationScope: CoroutineScope,
    private val tokenHolder: TokenHolder,
    private val dataStore: EncryptedDataStore,
    @Named("refresh") private val authApi: AuthApi,
    private val sessionManager: SessionManager,
    private val appDatabase: AppDatabase,
    private val cookieJar: EncryptedCookieJar,
) : Authenticator {

    companion object {
        private const val TAG = "TokenAuthenticator"

        /**
         * Durée maximale pendant laquelle un thread OkHttp attend le refresh partagé.
         * Au-delà, la requête d'origine échoue en 401 (pas de logout — le token n'est pas
         * invalide, le réseau est lent) ; le refresh continue dans [applicationScope].
         */
        internal const val AUTHENTICATE_TIMEOUT_MS = 8_000L
    }

    /** [AUTHENTICATE_TIMEOUT_MS] en production ; abaissé par les tests (évite 8 s d'attente). */
    @VisibleForTesting
    internal var authenticateTimeoutMs: Long = AUTHENTICATE_TIMEOUT_MS

    private val mutex = Mutex()
    private var refreshDeferred: Deferred<String?>? = null

    override fun authenticate(route: Route?, response: Response): Request? {
        // 1. Un seul retry par requête d'origine.
        if (response.priorResponse?.code == 401) {
            Timber.tag(TAG).w("TokenAuthenticator: still 401 after retry — giving up")
            return null
        }

        val path = response.request.url.encodedPath
        // 2. Le refresh lui-même est rejeté → refresh token invalide.
        if (path == AuthPaths.REFRESH) {
            Timber.tag(TAG).w("TokenAuthenticator: refresh endpoint returned 401 — forcing logout")
            handleLogout()
            return null
        }
        // 3. Autres endpoints publics : un 401 y est un échec métier, pas une expiration.
        if (path in AuthPaths.PUBLIC) return null

        // 4. Bearer périmé : une autre requête a déjà rafraîchi le token.
        val failedToken = response.request.header("Authorization")?.removePrefix("Bearer ")
        val current = tokenHolder.accessToken
        if (current != null && current != failedToken) {
            Timber.tag(TAG).d("TokenAuthenticator: stale bearer — retrying with current token")
            return retryWith(response, current)
        }

        // 5. Refresh partagé ; le timeout n'annule que cette attente.
        val newToken = runBlocking {
            withTimeoutOrNull(authenticateTimeoutMs) { refreshOnce(failedToken).await() }
        }
        if (newToken == null) {
            Timber.tag(TAG).w("TokenAuthenticator: no token after refresh (failure or timeout) — no retry")
            return null
        }
        return retryWith(response, newToken)
    }

    private fun retryWith(response: Response, token: String): Request =
        response.request.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()

    /**
     * Retourne le refresh en vol s'il n'est pas terminé, sinon en lance un nouveau.
     * Le lock ne garde que le champ — jamais l'attente réseau.
     *
     * Re-vérifie [TokenHolder] sous le lock : un refresh qui vient de se terminer a déjà écrit
     * le nouveau token (setToken précède la complétion du Deferred). Ferme la course
     * « bearer lu identique → Deferred terminé entre-temps → second refresh inutile ».
     */
    private suspend fun refreshOnce(failedToken: String?): Deferred<String?> = mutex.withLock {
        val inFlight = refreshDeferred
        if (inFlight != null && !inFlight.isCompleted) return@withLock inFlight

        val current = tokenHolder.accessToken
        if (current != null && current != failedToken) return@withLock CompletableDeferred(current)

        applicationScope.async { doRefresh() }.also { refreshDeferred = it }
    }

    private suspend fun doRefresh(): String? {
        return try {
            Timber.tag(TAG).d("TokenAuthenticator: refreshing access token")
            val response = authApi.refresh()
            if (response.isSuccessful) {
                val newToken = response.body()?.accessToken
                if (newToken.isNullOrBlank()) {
                    // 2xx sans token — réponse anormale, possiblement transitoire : pas de
                    // logout, pas de retry. Le prochain 401 relancera un cycle.
                    Timber.tag(TAG).w(
                        "TokenAuthenticator: refresh response 2xx but token is null/blank — skipping retry"
                    )
                    return null
                }
                // Cache mémoire AVANT le disque : le holder est toujours au moins aussi frais que
                // le DataStore (process tué entre les deux → ancien token relu → 401 → refresh).
                tokenHolder.setToken(newToken)
                dataStore.writeString(DataStoreKeys.ACCESS_TOKEN, newToken)
                Timber.tag(TAG).d("TokenAuthenticator: token refreshed successfully")
                newToken
            } else {
                Timber.tag(TAG).w("TokenAuthenticator: refresh failed (${response.code()}) — forcing logout")
                handleLogout()
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "TokenAuthenticator: refresh exception")
            null
        }
    }

    private fun handleLogout() {
        tokenHolder.clear()
        cookieJar.clear()
        runBlocking {
            try { appDatabase.clearAllTables() } catch (_: Exception) {}
            dataStore.clearSession()  // efface tokens/cookies/is_admin/portfolio_id — préserve WG_* + SETUP_COMPLETED
        }
        Timber.tag(TAG).w("TokenAuthenticator: forced logout — all session data cleared")
        FirebaseCrashlytics.getInstance().apply {
            setCustomKey("forced_logout_source", "TokenAuthenticator")
            log("Forced logout triggered — refresh token invalidated")
        }
        sessionManager.notifyForcedLogout()
    }
}
