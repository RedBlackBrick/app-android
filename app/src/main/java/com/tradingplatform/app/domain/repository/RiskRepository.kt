package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.PortfolioCircuitBreakerStatus

interface RiskRepository {
    suspend fun getPortfolioCircuitBreakerStatus(
        portfolioId: String,
    ): Result<PortfolioCircuitBreakerStatus>
}
