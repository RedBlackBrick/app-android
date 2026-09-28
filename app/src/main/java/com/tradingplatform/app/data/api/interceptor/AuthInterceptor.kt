package com.tradingplatform.app.data.api.interceptor

import com.tradingplatform.app.BuildConfig
import com.tradingplatform.app.data.api.AuthPaths
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
 * Le token est lu depuis [TokenHolder] uniquement (volatile read, ~0ns) — aucun accès
 * disque sur le thread OkHttp, et pas de fallback DataStore. Le holder est peuplé par le
 * preload de `TradingApplication.onCreate`, par `GetAuthContextUseCase` (porte de
 * navigation au démarrage, si le preload n'a pas encore fini), par AuthRepositoryImpl au
 * login / 2FA, et maintenu par [TokenAuthenticator] sur refresh.
 *
 * Si le token est absent (logout en vol, EncryptedDataStore corrompu ou Keystore
 * invalidé), un logout forcé est déclenché via [SessionManager] et une réponse 401
 * synthétique est retournée — la requête n'est pas envoyée au serveur.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenHolder: TokenHolder,
    private val sessionManager: SessionManager,
) : Interceptor {

    companion object {
        private const val TAG = "AuthInterceptor"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        // Public endpoints (AuthPaths.PUBLIC, miroir de auth.py PUBLIC_PATHS) : pas de
        // Bearer. Une install fraîche n'a aucun token et doit joindre /v1/auth/login ;
        // pendant un login 2FA le TokenHolder est vide et /v1/auth/2fa/verify
        // s'authentifie par le temp token du body.
        val path = chain.request().url.encodedPath
        if (path in AuthPaths.PUBLIC) {
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

        // TokenHolder vide : le token a été invalidé (logout en vol) ou le Keystore est
        // corrompu. Le cold start n'est pas un cas attendu : la porte de navigation attend
        // GetAuthContextUseCase, qui peuple le holder si le preload n'a pas encore fini
        // (seuls les appels hors UI — Worker, FCM — peuvent encore précéder le preload).
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
