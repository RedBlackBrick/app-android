package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.BrokerConnection

interface BrokerConnectionRepository {
    suspend fun getConnections(deviceId: String): Result<List<BrokerConnection>>
}
