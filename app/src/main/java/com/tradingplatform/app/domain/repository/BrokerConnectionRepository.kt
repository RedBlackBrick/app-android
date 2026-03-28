package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.BrokerConnection
import com.tradingplatform.app.domain.model.BrokerTestResult

interface BrokerConnectionRepository {
    suspend fun getConnections(deviceId: String): Result<List<BrokerConnection>>
    suspend fun deploy(deviceId: String, brokerCode: String, portfolioId: String?): Result<BrokerConnection>
    suspend fun remove(deviceId: String, portfolioId: String): Result<Unit>
    suspend fun test(deviceId: String): Result<BrokerTestResult>
}
