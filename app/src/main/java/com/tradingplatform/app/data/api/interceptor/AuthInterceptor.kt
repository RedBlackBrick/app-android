package com.tradingplatform.app.data.api.interceptor

import com.tradingplatform.app.BuildConfig
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.data.local.datastore.SecureReadResult
import com.tradingplatform.app.data.session.SessionManager
import com.tradingplatform.app.data.session.TokenHolder
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Injecte :
 * - Authorization: Bearer <access_token> (depuis [TokenHolder] in-memory, fallback [EncryptedDataStore])
 * - X-App-Version: {versionCode} (pour détection upgrade requis 426)
 *
 * Le token est lu prioritairement depuis [TokenHolder] (volatile read, ~0ns) pour éviter
 * l'IO disque EncryptedSharedPreferences sur chaque requête. Le fallback DataStore ne se
 * produit qu'au cold start (process kill → restart) et peuple le cache pour les requêtes
 * suivantes.
 *
 * Si le token est absent (EncryptedDataStore corrompu ou Keystore invalidé),
 * un logout forcé est déclenché via [SessionManager] et une réponse 401
 * synthétique est retournée — la requête n'est pas envoyée au serveur.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenHolder: TokenHolder,
    private val dataStore: EncryptedDataStore,
    private val sessionManager: SessionManager,
) : Interceptor {

    companion object {
        private const val TAG = "AuthInterceptor"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        // Hot path : lecture volatile ~0ns (pas d'IO disque)
        val cachedToken = tokenHolder.accessToken
        if (cachedToken != null) {
            return chain.proceed(
                chain.request().newBuilder()
                    .header("Authorization", "Bearer $cachedToken")
                    .header("X-App-Version", BuildConfig.VERSION_CODE.toString())
                    .build()
            )
        }

        // Cold start fallback : le process a ete tue, TokenHolder est vide.
        // Utilise readStringSafe() pour distinguer "absent" de "corrompu" (R1 fix).
        val readResult = runBlocking { dataStore.readStringSafe(DataStoreKeys.ACCESS_TOKEN) }

        return when (readResult) {
            is SecureReadResult.Found -> {
                tokenHolder.setToken(readResult.value)
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer ${readResult.value}")
                        .header("X-App-Version", BuildConfig.VERSION_CODE.toString())
                        .build()
                )
            }
            is SecureReadResult.NotFound -> {
                Timber.tag(TAG).w("AuthInterceptor: access token absent — forced logout")
                sessionManager.notifyForcedLogout()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(401)
                    .message("Unauthorized — token absent, session terminee")
                    .body("".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            is SecureReadResult.Corrupted -> {
                Timber.tag(TAG).e(
                    readResult.cause,
                    "AuthInterceptor: Keystore corrupted — forced logout with corruption flag"
                )
                sessionManager.notifyKeystoreCorruption()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(401)
                    .message("Unauthorized — Keystore corrupted")
                    .body("".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        }
    }
}
