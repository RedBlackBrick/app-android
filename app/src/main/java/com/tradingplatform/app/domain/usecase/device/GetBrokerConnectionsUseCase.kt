package com.tradingplatform.app.domain.usecase.device

import com.tradingplatform.app.domain.model.BrokerConnection
import com.tradingplatform.app.domain.repository.BrokerConnectionRepository
import javax.inject.Inject

class GetBrokerConnectionsUseCase @Inject constructor(
    private val repository: BrokerConnectionRepository,
) {
    suspend operator fun invoke(deviceId: String): Result<List<BrokerConnection>> =
        repository.getConnections(deviceId)
}
