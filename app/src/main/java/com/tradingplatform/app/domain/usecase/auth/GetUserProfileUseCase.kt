package com.tradingplatform.app.domain.usecase.auth

import com.tradingplatform.app.domain.model.User
import com.tradingplatform.app.domain.repository.AuthRepository
import javax.inject.Inject

class GetUserProfileUseCase @Inject constructor(
    private val repository: AuthRepository,
) {
    suspend operator fun invoke(): Result<User> = repository.getUserProfile()
}
