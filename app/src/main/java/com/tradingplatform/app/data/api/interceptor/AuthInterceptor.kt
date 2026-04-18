package com.tradingplatform.app.data.api.interceptor

import com.tradingplatform.app.BuildConfig
import com.tradingplatform.app.data.session.SessionManager
import com.tradingplatform.app.data.session.TokenHolder
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
 * - Authorization: Bearer <access_token> (depuis [TokenHolder] in-memory uniquement)
 * - X-App-Version: {versionCode} (pour détection upgrade requis 426)
 *
 * Le token est lu depuis [TokenHolder] (volatile read, ~0ns) — aucun accès disque
 * sur le thread OkHttp. Le cache est peuplé au startup par [TradingApplication.onCreate]
 * et maintenu par [TokenAuthenticator] sur refresh.
 *
 * Si le token est absent (cold start non encore terminé, EncryptedDataStore corrompu
 * ou Keystore invalidé), un logout forcé est déclenché via [SessionManager] et une
 * réponse 401 synthétique est retournée — la requête n'est pas envoyée au serveur.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenHolder: TokenHolder,
    private val sessionManager: SessionManager,
) : Interceptor {

    companion object {
        private const val TAG = "AuthInterceptor"

        // Endpoints that do NOT require an Authorization header. Fresh installs
        // have no token yet; without this exclusion, the interceptor short-circuits
        // with a synthetic 401 before /v1/auth/login can even reach the server.
        // Must stay in sync with the backend CSRF_EXEMPT_PATHS in
        // app/core/middleware/csrf.py.
        private val PUBLIC_PATHS: Set<String> = setOf(
            "/v1/auth/login",
            "/v1/auth/register",
            "/v1/auth/refresh",
            "/v1/auth/csrf-token",
        )
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        // Public endpoints: proceed without Authorization header (fresh installs
        // have no token at all and must be able to reach /v1/auth/login).
        val path = chain.request().url.encodedPath
        if (path in PUBLIC_PATHS) {
            return chain.proceed(
                chain.request().newBuilder()
                    .header("X-App-Version", BuildConfig.VERSION_CODE.toString())
                    .build()
            )
        }

        // Hot path : lecture volatile ~0ns (pas d'IO disque)
        // Le cache est pré-chargé par TradingApplication au startup.
        val cachedToken = tokenHolder.accessToken
        if (cachedToken != null) {
            return chain.proceed(
                chain.request().newBuilder()
                    .header("Authorization", "Bearer $cachedToken")
                    .header("X-App-Version", BuildConfig.VERSION_CODE.toString())
                    .build()
            )
        }

        // TokenHolder vide : soit le preload n'est pas encore terminé (rare, course
        // improbable — preload démarre au super.onCreate() avant toute composition UI),
        // soit le token a été invalidé (logout en vol) ou le Keystore est corrompu.
        // Dans tous les cas : refuser la requête sans bloquer un thread OkHttp.
        Timber.tag(TAG).w("AuthInterceptor: token absent in TokenHolder — forced logout")
        sessionManager.notifyForcedLogout()
        return Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized — token absent, session terminee")
            .body("".toResponseBody("application/json".toMediaType()))
            .build()
    }
}
