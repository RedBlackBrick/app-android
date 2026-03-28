package com.tradingplatform.app.data.repository

import com.squareup.moshi.Moshi
import com.tradingplatform.app.data.api.AuthApi
import com.tradingplatform.app.data.api.interceptor.CsrfInterceptor
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.data.model.ApiErrorDto
import com.tradingplatform.app.data.model.LoginRequestDto
import com.tradingplatform.app.data.model.TotpRequiredErrorDto
import com.tradingplatform.app.data.model.TotpVerifyRequestDto
import com.tradingplatform.app.data.model.toDomain
import com.tradingplatform.app.domain.exception.AccountLockedException
import com.tradingplatform.app.domain.exception.InvalidCredentialsException
import com.tradingplatform.app.domain.exception.InvalidTotpCodeException
import com.tradingplatform.app.domain.exception.TotpRequiredException
import com.tradingplatform.app.domain.model.AuthTokens
import com.tradingplatform.app.domain.model.Portfolio
import com.tradingplatform.app.domain.model.User
import com.tradingplatform.app.domain.model.WsTokenInfo
import com.tradingplatform.app.data.session.TokenHolder
import com.tradingplatform.app.domain.repository.AuthRepository
import java.time.Instant
import okhttp3.OkHttpClient
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val authApi: AuthApi,
    private val tokenHolder: TokenHolder,
    private val dataStore: EncryptedDataStore,
    private val moshi: Moshi,
    private val csrfInterceptor: CsrfInterceptor,
    private val okHttpClient: OkHttpClient,
) : AuthRepository {

    companion object {
        private const val TAG = "AuthRepositoryImpl"
    }

    override suspend fun login(email: String, password: String): Result<Pair<User, AuthTokens>> =
        runCatching {
            val response = authApi.login(LoginRequestDto(email, password))
            if (!response.isSuccessful) {
                val errorBody = response.errorBody()?.string()
                val code = response.code()
                val retryAfter = response.headers()["Retry-After"]?.toIntOrNull()

                // 429 : compte verrouillé sans body nécessaire — lire Retry-After
                if (code == 429) throw AccountLockedException(retryAfterSeconds = retryAfter)

                // Parser le corps d'erreur une seule fois et router selon le code API
                parseLoginError(errorBody, retryAfter)?.let { throw it }

                error("Login failed: HTTP $code")
            }
            val body = response.body() ?: error("Empty login response")

            // Le backend retourne HTTP 200 avec requires_2fa=true quand le TOTP est activé.
            // Les champs user/tokens sont absents dans ce cas — détecter avant de parser.
            if (body.requires2fa) {
                val sessionToken = body.sessionToken ?: body.tempToken
                    ?: error("2FA required but no session_token in response")
                throw TotpRequiredException(sessionToken = sessionToken)
            }

            val user = body.user?.toDomain() ?: error("Login response missing user")
            val tokens = body.tokens?.toDomain() ?: error("Login response missing tokens")

            // Peupler le cache mémoire AVANT le DataStore (disque) — si le process est tué
            // entre les deux, le fallback DataStore relira l'ancien token → nouveau 401 → refresh.
            tokenHolder.setToken(tokens.accessToken)
            dataStore.writeString(DataStoreKeys.ACCESS_TOKEN, tokens.accessToken)
            dataStore.writeLong(DataStoreKeys.USER_ID, user.id)
            dataStore.writeBoolean(DataStoreKeys.IS_ADMIN, user.isAdmin)

            // Pre-fetch CSRF pour éviter runBlocking contention sur la première requête POST
            csrfInterceptor.preFetch()

            Pair(user, tokens)
        }

    override suspend fun logout(): Result<Unit> = runCatching {
        // Tenter le logout API — même en cas d'erreur réseau, nettoyer le store local
        try {
            authApi.logout()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "AuthRepository: logout API call failed, clearing local data anyway")
        }
        // Invalider le cache mémoire AVANT le DataStore — empêche AuthInterceptor d'utiliser
        // un token périmé pendant que clearAll() est en cours (IO disque).
        tokenHolder.clear()
        // clearAll() wrappé séparément — on doit toujours réussir le logout local
        // même si EncryptedSharedPreferences est corrompu ou le Keystore invalidé
        try {
            dataStore.clearAll()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "AuthRepository: clearAll failed during logout — session data may be stale")
        }
        csrfInterceptor.clearToken()
        try { okHttpClient.cache?.evictAll() } catch (_: Exception) {}
    }

    override suspend fun verify2fa(sessionToken: String, totpCode: String): Result<Pair<User, AuthTokens>> =
        runCatching {
            val response = authApi.verify2fa(TotpVerifyRequestDto(sessionToken, totpCode))
            if (!response.isSuccessful) {
                // Code TOTP invalide (401) → exception typée
                if (response.code() == 401) {
                    throw InvalidTotpCodeException()
                }
                error("2FA verification failed: HTTP ${response.code()}")
            }
            val body = response.body() ?: error("Empty 2FA verify response")
            val userDto = body.user ?: error("2FA verify response missing user")
            val tokensDto = body.tokens ?: error("2FA verify response missing tokens")

            val user = userDto.toDomain()
            val tokens = tokensDto.toDomain()

            // Persist user data and tokens after successful 2FA (same as login)
            tokenHolder.setToken(tokens.accessToken)
            dataStore.writeString(DataStoreKeys.ACCESS_TOKEN, tokens.accessToken)
            dataStore.writeLong(DataStoreKeys.USER_ID, user.id)
            dataStore.writeBoolean(DataStoreKeys.IS_ADMIN, user.isAdmin)

            // Pre-fetch CSRF pour éviter runBlocking contention sur la première requête POST
            csrfInterceptor.preFetch()

            Pair(user, tokens)
        }

    override suspend fun getPortfolios(): Result<List<Portfolio>> = runCatching {
        val response = authApi.getPortfolios()
        if (!response.isSuccessful) {
            error("Get portfolios failed: HTTP ${response.code()}")
        }
        val portfolios = response.body()?.map { it.toDomain() } ?: emptyList()

        when {
            portfolios.isEmpty() -> {
                Timber.tag(TAG).e("AuthRepository: empty portfolio list — incoherent server state")
                error("No portfolio found")
            }
            portfolios.size > 1 -> {
                Timber.tag(TAG).w("AuthRepository: [PORTFOLIO_MULTI] count=${portfolios.size}, using portfolios[0]")
            }
        }

        // Persister le portfolioId pour réutilisation sans re-fetch
        dataStore.writeString(DataStoreKeys.PORTFOLIO_ID, portfolios[0].id)

        portfolios
    }

    /**
     * Parse le corps d'erreur d'un appel login et retourne l'exception typée correspondante,
     * ou null si le corps est absent / non reconnu.
     *
     * Règles de priorité :
     * 1. AUTH_1004 → TotpRequiredException (corps TotpRequiredErrorDto, contient session_token)
     * 2. AUTH_1008 → AccountLockedException (retryAfterSeconds depuis l'en-tête Retry-After)
     * 3. AUTH_1001 → InvalidCredentialsException
     *
     * Le corps est parsé une seule fois via TotpRequiredErrorDto (super-set de ApiErrorDto).
     * Si le parsing échoue, retourne null et laisse l'appelant émettre une erreur générique.
     */
    private fun parseLoginError(errorBody: String?, retryAfter: Int?): Exception? {
        if (errorBody.isNullOrBlank()) return null
        return try {
            // TotpRequiredErrorDto contient error_code + session_token — tenter ce parsing en premier.
            val totpError = moshi.adapter(TotpRequiredErrorDto::class.java).fromJson(errorBody)
            when (totpError?.errorCode) {
                "AUTH_1004" -> TotpRequiredException(sessionToken = totpError.sessionToken)
                "AUTH_1008", "LOGIN_RATE_LIMITED" -> AccountLockedException(retryAfterSeconds = retryAfter)
                "AUTH_1001", "INVALID_CREDENTIALS" -> InvalidCredentialsException()
                else -> {
                    // Session_token absent ou errorCode non reconnu — fallback sur ApiErrorDto générique
                    val apiError = moshi.adapter(ApiErrorDto::class.java).fromJson(errorBody)
                    when (apiError?.errorCode) {
                        "AUTH_1008", "LOGIN_RATE_LIMITED" -> AccountLockedException(retryAfterSeconds = retryAfter)
                        "AUTH_1001", "INVALID_CREDENTIALS" -> InvalidCredentialsException()
                        else -> null
                    }
                }
            }
        } catch (_: Exception) {
            // Parsing Moshi échoué — le corps n'est pas du JSON valide
            null
        }
    }

    override suspend fun refreshToken(): Result<AuthTokens> = runCatching {
        val response = authApi.refresh()
        if (!response.isSuccessful) {
            error("Token refresh failed: HTTP ${response.code()}")
        }
        val body = response.body() ?: error("Empty refresh response")
        val tokens = body.toDomain()
        tokenHolder.setToken(tokens.accessToken)
        dataStore.writeString(DataStoreKeys.ACCESS_TOKEN, tokens.accessToken)
        tokens
    }

    override suspend fun getWsToken(): Result<WsTokenInfo> = runCatching {
        val response = authApi.getWsToken()
        if (!response.isSuccessful) {
            error("WS token fetch failed: HTTP ${response.code()}")
        }
        val body = response.body() ?: error("Empty WS token response")
        WsTokenInfo(
            token = body.token,
            expiresAt = Instant.parse(body.expiresAt),
        )
    }

    override suspend fun getUserProfile(): Result<User> = runCatching {
        val response = authApi.me()
        if (!response.isSuccessful) {
            error("Get user profile failed: HTTP ${response.code()}")
        }
        response.body()?.toDomain() ?: error("Empty user profile response")
    }
}
