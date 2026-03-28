package com.tradingplatform.app.domain.usecase.device

import com.tradingplatform.app.domain.repository.BrokerConnectionRepository
import javax.inject.Inject

class RemoveBrokerConnectionUseCase @Inject constructor(
    private val repository: BrokerConnectionRepository,
) {
    suspend operator fun invoke(deviceId: String, portfolioId: String): Result<Unit> =
        repository.remove(deviceId, portfolioId)
}
