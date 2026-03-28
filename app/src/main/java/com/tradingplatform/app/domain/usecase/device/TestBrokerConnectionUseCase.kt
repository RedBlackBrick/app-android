package com.tradingplatform.app.domain.usecase.device

import com.tradingplatform.app.domain.model.BrokerTestResult
import com.tradingplatform.app.domain.repository.BrokerConnectionRepository
import javax.inject.Inject

class TestBrokerConnectionUseCase @Inject constructor(
    private val repository: BrokerConnectionRepository,
) {
    suspend operator fun invoke(deviceId: String): Result<BrokerTestResult> =
        repository.test(deviceId)
}
