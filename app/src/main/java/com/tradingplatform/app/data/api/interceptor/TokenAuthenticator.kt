package com.tradingplatform.app.data.api.interceptor

import com.tradingplatform.app.data.api.AuthApi
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val dataStore: EncryptedDataStore,
    private val authApi: dagger.Lazy<AuthApi>,
) : Authenticator {

    private val mutex = Mutex()
    private var refreshDeferred: Deferred<String?>? = null

    override fun authenticate(route: Route?, response: Response): Request? {
        // Éviter la boucle infinie si le refresh lui-même retourne 401
        if (response.request.url.encodedPath == "/v1/auth/refresh") {
            Timber.w("TokenAuthenticator: refresh endpoint returned 401 — forcing logout")
            handleLogout()
            return null
        }

        val newToken = runBlocking {
            mutex.withLock {
                val existing = refreshDeferred
                if (existing != null) {
                    // Un refresh est déjà en vol — réutiliser son résultat
                    Timber.d("TokenAuthenticator: reusing in-flight refresh")
                    existing.await()
                } else {
                    val deferred = applicationScope.async { doRefresh() }
                    refreshDeferred = deferred
                    val token = deferred.await()
                    refreshDeferred = null
                    token
                }
            }
        } ?: return null  // refresh échoué → ne pas retry

        return response.request.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .build()
    }

    private suspend fun doRefresh(): String? {
        return try {
            Timber.d("TokenAuthenticator: refreshing access token")
            val response = authApi.get().refresh()
            if (response.isSuccessful) {
                val newToken = response.body()?.accessToken
                if (newToken != null) {
                    dataStore.writeString(DataStoreKeys.ACCESS_TOKEN, newToken)
                    Timber.d("TokenAuthenticator: token refreshed successfully")
                }
                newToken
            } else {
                Timber.w("TokenAuthenticator: refresh failed (${response.code()}) — forcing logout")
                handleLogout()
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "TokenAuthenticator: refresh exception")
            null
        }
    }

    private fun handleLogout() {
        runBlocking {
            dataStore.clearAll()  // efface tous les tokens, cookies, is_admin, portfolio_id
        }
        Timber.w("TokenAuthenticator: forced logout — all session data cleared")
    }
}
