package com.tradingplatform.app.data.repository

import com.squareup.moshi.Moshi
import com.tradingplatform.app.data.api.AuthApi
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
import com.tradingplatform.app.domain.repository.AuthRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val authApi: AuthApi,
    private val dataStore: EncryptedDataStore,
    private val moshi: Moshi,
) : AuthRepository {

    override suspend fun login(email: String, password: String): Result<Pair<User, AuthTokens>> =
        runCatching {
            val response = authApi.login(LoginRequestDto(email, password))
            if (!response.isSuccessful) {
                val errorBody = response.errorBody()?.string()
                val code = response.code()

                // AUTH_1004 : 2FA requis — parser le session_token dans le corps d'erreur
                if (code == 401 && errorBody != null) {
                    try {
                        val totpError = moshi.adapter(TotpRequiredErrorDto::class.java)
                            .fromJson(errorBody)
                        if (totpError?.errorCode == "AUTH_1004") {
                            throw TotpRequiredException(sessionToken = totpError.sessionToken)
                        }
                        val apiError = moshi.adapter(ApiErrorDto::class.java).fromJson(errorBody)
                        if (apiError?.errorCode == "AUTH_1001") {
                            throw InvalidCredentialsException()
                        }
                    } catch (e: TotpRequiredException) {
                        throw e
                    } catch (e: InvalidCredentialsException) {
                        throw e
                    } catch (_: Exception) {
                        // Parsing échoué — erreur générique
                    }
                }

                // 429 : compte verrouillé — lire Retry-After
                if (code == 429) {
                    val retryAfter = response.headers()["Retry-After"]?.toIntOrNull()
                    throw AccountLockedException(retryAfterSeconds = retryAfter)
                }

                // Vérifier AUTH_1008 dans le corps (peut arriver en 401 aussi)
                if (errorBody != null) {
                    try {
                        val apiError = moshi.adapter(ApiErrorDto::class.java).fromJson(errorBody)
                        if (apiError?.errorCode == "AUTH_1008") {
                            val retryAfter = response.headers()["Retry-After"]?.toIntOrNull()
                            throw AccountLockedException(retryAfterSeconds = retryAfter)
                        }
                        if (apiError?.errorCode == "AUTH_1001") {
                            throw InvalidCredentialsException()
                        }
                    } catch (e: AccountLockedException) {
                        throw e
                    } catch (e: InvalidCredentialsException) {
                        throw e
                    } catch (_: Exception) {
                        // Parsing échoué — erreur générique
                    }
                }

                error("Login failed: HTTP $code")
            }
            val body = response.body() ?: error("Empty login response")
            val user = body.user.toDomain()
            val tokens = body.tokens.toDomain()

            // Persister les données utilisateur et le token après login réussi
            dataStore.writeString(DataStoreKeys.ACCESS_TOKEN, tokens.accessToken)
            dataStore.writeLong(DataStoreKeys.USER_ID, user.id)
            dataStore.writeBoolean(DataStoreKeys.IS_ADMIN, user.isAdmin)

            Pair(user, tokens)
        }

    override suspend fun logout(): Result<Unit> = runCatching {
        // Tenter le logout API — même en cas d'erreur réseau, nettoyer le store local
        try {
            authApi.logout()
        } catch (e: Exception) {
            Timber.w(e, "AuthRepository: logout API call failed, clearing local data anyway")
        }
        dataStore.clearAll()
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
            dataStore.writeString(DataStoreKeys.ACCESS_TOKEN, tokens.accessToken)
            dataStore.writeLong(DataStoreKeys.USER_ID, user.id)
            dataStore.writeBoolean(DataStoreKeys.IS_ADMIN, user.isAdmin)

            Pair(user, tokens)
        }

    override suspend fun getPortfolios(): Result<List<Portfolio>> = runCatching {
        val response = authApi.getPortfolios()
        if (!response.isSuccessful) {
            error("Get portfolios failed: HTTP ${response.code()}")
        }
        val portfolios = response.body()?.portfolios?.map { it.toDomain() } ?: emptyList()

        when {
            portfolios.isEmpty() -> {
                Timber.e("AuthRepository: empty portfolio list — incoherent server state")
                error("No portfolio found")
            }
            portfolios.size > 1 -> {
                Timber.w("AuthRepository: [PORTFOLIO_MULTI] count=${portfolios.size}, using portfolios[0]")
            }
        }

        // Persister le portfolioId pour réutilisation sans re-fetch
        dataStore.writeString(DataStoreKeys.PORTFOLIO_ID, portfolios[0].id)

        portfolios
    }

    override suspend fun refreshToken(): Result<AuthTokens> = runCatching {
        val response = authApi.refresh()
        if (!response.isSuccessful) {
            error("Token refresh failed: HTTP ${response.code()}")
        }
        val body = response.body() ?: error("Empty refresh response")
        val tokens = body.toDomain()
        dataStore.writeString(DataStoreKeys.ACCESS_TOKEN, tokens.accessToken)
        tokens
    }

    override suspend fun getWsToken(): Result<String> = runCatching {
        val response = authApi.getWsToken()
        if (!response.isSuccessful) {
            error("WS token fetch failed: HTTP ${response.code()}")
        }
        val body = response.body() ?: error("Empty WS token response")
        body.token
    }
}
