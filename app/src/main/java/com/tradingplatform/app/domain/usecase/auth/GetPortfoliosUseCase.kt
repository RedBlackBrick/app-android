package com.tradingplatform.app.domain.usecase.auth

import com.tradingplatform.app.domain.model.Portfolio
import com.tradingplatform.app.domain.repository.AuthRepository
import javax.inject.Inject

class GetPortfoliosUseCase @Inject constructor(
    private val authRepository: AuthRepository,
) {
    suspend operator fun invoke(): Result<List<Portfolio>> =
        authRepository.getPortfolios()
}
