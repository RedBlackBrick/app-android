package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.AuthTokens
import com.tradingplatform.app.domain.model.Portfolio
import com.tradingplatform.app.domain.model.User
import com.tradingplatform.app.domain.model.WsTokenInfo

interface AuthRepository {
    suspend fun login(email: String, password: String): Result<Pair<User, AuthTokens>>
    suspend fun logout(): Result<Unit>
    suspend fun verify2fa(sessionToken: String, totpCode: String): Result<Pair<User, AuthTokens>>
    suspend fun getPortfolios(): Result<List<Portfolio>>
    suspend fun refreshToken(): Result<AuthTokens>

    /**
     * Retourne un JWT avec claim `type=websocket` et son expiration pour authentifier
     * la connexion WS privée et planifier le refresh proactif.
     * Ce token est distinct de l'access token — il est obtenu via `POST /v1/auth/ws-token`.
     */
    suspend fun getWsToken(): Result<WsTokenInfo>

    /** Fetches the current user's profile from `GET /v1/auth/me`. */
    suspend fun getUserProfile(): Result<User>
}
