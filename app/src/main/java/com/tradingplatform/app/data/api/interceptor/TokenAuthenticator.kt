package com.tradingplatform.app.data.api.interceptor

import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.tradingplatform.app.data.api.AuthApi
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.data.local.db.AppDatabase
import com.tradingplatform.app.data.session.SessionManager
import com.tradingplatform.app.data.session.TokenHolder
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
import javax.inject.Singleton

/**
 * Gère le refresh transparent du JWT access token sur réception d'un 401 AUTH_1002.
 *
 * Pattern concurrent : Mutex + Deferred pour éviter N refreshes simultanés.
 * Si plusieurs requêtes reçoivent 401 en même temps, une seule lance le refresh —
 * les autres attendent et réutilisent le nouveau token.
 *
 * applicationScope : @Singleton scope injecté depuis AppModule (SupervisorJob + Dispatchers.IO).
 * Pas de delay — les threads attendent sur le Deferred, pas un timer.
 *
 * authApi : dagger.Lazy pour éviter la dépendance circulaire
 * OkHttpClient → TokenAuthenticator → AuthApi → OkHttpClient.
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val applicationScope: CoroutineScope,
    private val tokenHolder: TokenHolder,
    private val dataStore: EncryptedDataStore,
    private val authApi: dagger.Lazy<AuthApi>,
    private val sessionManager: SessionManager,
    private val appDatabase: AppDatabase,
    private val cookieJar: EncryptedCookieJar,
) : Authenticator {

    companion object {
        private const val TAG = "TokenAuthenticator"
        /**
         * Timeout global pour le runBlocking (mutex + refresh réseau).
         *
         * Réduit à 8s pour libérer plus vite les threads du pool OkHttp en cas de
         * réseau saturé — si le refresh n'aboutit pas en 8s, la requête échoue et
         * l'UI affichera l'erreur. Le prochain appel déclenchera un nouveau cycle.
         */
        private const val AUTHENTICATE_TIMEOUT_MS = 8_000L
    }

    private val mutex = Mutex()
    private var refreshDeferred: Deferred<String?>? = null

    override fun authenticate(route: Route?, response: Response): Request? {
        // Éviter la boucle infinie si le refresh lui-même retourne 401
        if (response.request.url.encodedPath == "/v1/auth/refresh") {
            Timber.tag(TAG).w("TokenAuthenticator: refresh endpoint returned 401 — forcing logout")
            handleLogout()
            return null
        }

        val newToken = runBlocking {
            // Timeout global : mutex acquisition + refresh réseau.
            // Sur réseau lent (VPN 4G), 15s laisse le temps au refresh HTTP (OkHttp a ses propres
            // timeouts de 30s, mais le mutex.withLock peut bloquer si un autre refresh est en vol).
            // En cas de timeout : retourne null → la requête originale échoue en 401 → l'UI affiche
            // l'erreur. Pas de logout forcé — le token n'est pas invalide, le réseau est lent.
            withTimeoutOrNull(AUTHENTICATE_TIMEOUT_MS) {
                mutex.withLock {
                    val existing = refreshDeferred
                    if (existing != null) {
                        // Un refresh est déjà en vol — réutiliser son résultat
                        Timber.tag(TAG).d("TokenAuthenticator: reusing in-flight refresh")
                        try {
                            existing.await()
                        } finally {
                            refreshDeferred = null
                        }
                    } else {
                        val deferred = applicationScope.async { doRefresh() }
                        refreshDeferred = deferred
                        try {
                            deferred.await()
                        } finally {
                            refreshDeferred = null
                        }
                    }
                }
            } ?: run {
                Timber.tag(TAG).w("TokenAuthenticator: timeout after ${AUTHENTICATE_TIMEOUT_MS}ms — skipping retry (no logout)")
                null
            }
        } ?: return null  // refresh échoué ou timeout → ne pas retry

        return response.request.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .build()
    }

    private suspend fun doRefresh(): String? {
        return try {
            Timber.tag(TAG).d("TokenAuthenticator: refreshing access token")
            val response = authApi.get().refresh()
            if (response.isSuccessful) {
                val newToken = response.body()?.accessToken
                if (newToken.isNullOrBlank()) {
                    // Token null ou vide dans une réponse 2xx — état anormal du serveur.
                    // Ne pas logout immédiatement (pourrait être transitoire) — retourner null
                    // pour que OkHttp abandonne cette requête sans retry. Le prochain appel API
                    // déclenchera un nouveau cycle 401 → refresh.
                    Timber.tag(TAG).w(
                        "TokenAuthenticator: refresh response 2xx but token is null/blank — " +
                            "server returned anomalous response, skipping retry"
                    )
                    return null
                }
                // Écrire dans le cache mémoire AVANT le DataStore (disque) :
                // si le process est tué entre les deux, le fallback DataStore relira
                // l'ancien token → nouveau 401 → nouveau refresh. Pas de perte.
                tokenHolder.setToken(newToken)
                dataStore.writeString(DataStoreKeys.ACCESS_TOKEN, newToken)
                Timber.tag(TAG).d("TokenAuthenticator: token refreshed successfully")
                newToken
            } else {
                Timber.tag(TAG).w("TokenAuthenticator: refresh failed (${response.code()}) — forcing logout")
                handleLogout()
                null
            }
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
            dataStore.clearAll()  // efface tous les tokens, cookies, is_admin, portfolio_id
        }
        Timber.tag(TAG).w("TokenAuthenticator: forced logout — all session data cleared")
        FirebaseCrashlytics.getInstance().apply {
            setCustomKey("forced_logout_source", "TokenAuthenticator")
            log("Forced logout triggered — refresh token invalidated")
        }
        sessionManager.notifyForcedLogout()
    }
}
