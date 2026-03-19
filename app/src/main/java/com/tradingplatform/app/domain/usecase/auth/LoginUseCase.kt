package com.tradingplatform.app.domain.usecase.auth

import com.tradingplatform.app.domain.model.AuthTokens
import com.tradingplatform.app.domain.model.User
import com.tradingplatform.app.domain.repository.AuthRepository
import javax.inject.Inject

class LoginUseCase @Inject constructor(
    private val authRepository: AuthRepository,
) {
    suspend operator fun invoke(email: String, password: String): Result<Pair<User, AuthTokens>> =
        authRepository.login(email, password)
}
