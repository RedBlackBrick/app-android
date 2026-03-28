package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.BrokerConnectionApi
import com.tradingplatform.app.data.api.DeployBrokerRequestDto
import com.tradingplatform.app.domain.model.BrokerConnection
import com.tradingplatform.app.domain.model.BrokerTestResult
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

    override suspend fun deploy(
        deviceId: String,
        brokerCode: String,
        portfolioId: String?,
    ): Result<BrokerConnection> = runCatching {
        val response = api.deployBroker(
            DeployBrokerRequestDto(
                deviceId = deviceId,
                brokerCode = brokerCode,
                portfolioId = portfolioId,
            ),
        )
        if (!response.isSuccessful) {
            error("Deploy broker failed: HTTP ${response.code()}")
        }
        val dto = response.body() ?: error("Empty deploy response")
        BrokerConnection(
            deviceId = dto.deviceId,
            portfolioId = dto.portfolioId,
            brokerCode = dto.brokerCode,
            connectionStatus = dto.connectionStatus,
            executionMode = dto.executionMode,
        )
    }

    override suspend fun remove(deviceId: String, portfolioId: String): Result<Unit> = runCatching {
        val response = api.removeBroker(deviceId, portfolioId)
        if (!response.isSuccessful) {
            error("Remove broker failed: HTTP ${response.code()}")
        }
    }

    override suspend fun test(deviceId: String): Result<BrokerTestResult> = runCatching {
        val response = api.testBrokerConnection(deviceId)
        if (!response.isSuccessful) {
            error("Test broker connection failed: HTTP ${response.code()}")
        }
        val dto = response.body() ?: error("Empty test result")
        BrokerTestResult(healthy = dto.healthy, message = dto.message)
    }
}
