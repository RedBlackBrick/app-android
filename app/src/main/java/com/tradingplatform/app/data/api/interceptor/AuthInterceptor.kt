package com.tradingplatform.app.data.api.interceptor

import com.tradingplatform.app.BuildConfig
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.data.session.SessionManager
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
 * - Authorization: Bearer <access_token> (depuis EncryptedDataStore)
 * - X-App-Version: {versionCode} (pour détection upgrade requis 426)
 *
 * Si le token est absent (EncryptedDataStore corrompu ou Keystore invalidé),
 * un logout forcé est déclenché via [SessionManager] et une réponse 401
 * synthétique est retournée — la requête n'est pas envoyée au serveur.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val dataStore: EncryptedDataStore,
    private val sessionManager: SessionManager,
) : Interceptor {

    companion object {
        private const val TAG = "AuthInterceptor"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking {
            dataStore.readString(DataStoreKeys.ACCESS_TOKEN)
        }

        if (token == null) {
            Timber.tag(TAG).w("AuthInterceptor: access token absent (store corrompu ou Keystore invalidé) — forced logout")
            sessionManager.notifyForcedLogout()
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized — token absent, session terminée")
                .body("".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val request = chain.request().newBuilder()
            .header("Authorization", "Bearer $token")
            .header("X-App-Version", BuildConfig.VERSION_CODE.toString())
            .build()

        return chain.proceed(request)
    }
}
