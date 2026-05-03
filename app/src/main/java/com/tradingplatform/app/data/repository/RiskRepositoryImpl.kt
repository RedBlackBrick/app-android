package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.RiskApi
import com.tradingplatform.app.domain.model.CircuitBreakerState
import com.tradingplatform.app.domain.model.PortfolioCircuitBreakerStatus
import com.tradingplatform.app.domain.repository.RiskRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RiskRepositoryImpl @Inject constructor(
    private val api: RiskApi,
) : RiskRepository {

    override suspend fun getPortfolioCircuitBreakerStatus(
        portfolioId: String,
    ): Result<PortfolioCircuitBreakerStatus> = runCatching {
        val response = api.getPortfolioCircuitBreakerStatus(portfolioId)
        if (!response.isSuccessful) {
            error("Get portfolio circuit-breaker status failed: HTTP ${response.code()}")
        }
        val dto = response.body() ?: error("Empty circuit-breaker status response")
        PortfolioCircuitBreakerStatus(
            portfolioId = dto.portfolioId,
            enabled = dto.enabled,
            state = CircuitBreakerState.fromWire(dto.state),
            count = dto.count,
            threshold = dto.threshold,
            windowSeconds = dto.windowSeconds,
            ttlSeconds = dto.ttlSeconds,
            redisUnavailable = dto.redisUnavailable,
        )
    }
}
