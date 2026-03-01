package com.tradingplatform.app.domain.usecase.auth

import com.tradingplatform.app.domain.repository.AuthRepository
import javax.inject.Inject

class Verify2faUseCase @Inject constructor(
    private val authRepository: AuthRepository,
) {
    suspend operator fun invoke(sessionToken: String, totpCode: String): Result<Unit> =
        authRepository.verify2fa(sessionToken, totpCode)
}
