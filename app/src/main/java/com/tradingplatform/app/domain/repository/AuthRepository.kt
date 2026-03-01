package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.AuthTokens
import com.tradingplatform.app.domain.model.Portfolio
import com.tradingplatform.app.domain.model.User

interface AuthRepository {
    suspend fun login(email: String, password: String): Result<Pair<User, AuthTokens>>
    suspend fun logout(): Result<Unit>
    suspend fun verify2fa(sessionToken: String, totpCode: String): Result<Unit>
    suspend fun getPortfolios(): Result<List<Portfolio>>
    suspend fun refreshToken(): Result<AuthTokens>
}
