package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.BrokerConnectionApi
import com.tradingplatform.app.domain.model.BrokerConnection
import com.tradingplatform.app.domain.repository.BrokerConnectionRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BrokerConnectionRepositoryImpl @Inject constructor(
    private val api: BrokerConnectionApi,
) : BrokerConnectionRepository {

    override suspend fun getConnections(deviceId: String): Result<List<BrokerConnection>> = runCatching {
        val response = api.getBrokerConnections(deviceId)
        if (!response.isSuccessful) {
            error("Get broker connections failed: HTTP ${response.code()}")
        }
        response.body()?.map {
            BrokerConnection(
                deviceId = it.deviceId,
                portfolioId = it.portfolioId,
                brokerCode = it.brokerCode,
                connectionStatus = it.connectionStatus,
                executionMode = it.executionMode,
            )
        } ?: emptyList()
    }
}
