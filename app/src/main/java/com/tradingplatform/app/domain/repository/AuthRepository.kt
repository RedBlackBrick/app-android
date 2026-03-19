package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.AuthTokens
import com.tradingplatform.app.domain.model.Portfolio
import com.tradingplatform.app.domain.model.User

interface AuthRepository {
    suspend fun login(email: String, password: String): Result<Pair<User, AuthTokens>>
    suspend fun logout(): Result<Unit>
    suspend fun verify2fa(sessionToken: String, totpCode: String): Result<Pair<User, AuthTokens>>
    suspend fun getPortfolios(): Result<List<Portfolio>>
    suspend fun refreshToken(): Result<AuthTokens>

    /**
     * Retourne un JWT avec claim `type=websocket` pour authentifier la connexion WS privée.
     * Ce token est distinct de l'access token — il est obtenu via `POST /v1/auth/ws-token`.
     */
    suspend fun getWsToken(): Result<String>
}
