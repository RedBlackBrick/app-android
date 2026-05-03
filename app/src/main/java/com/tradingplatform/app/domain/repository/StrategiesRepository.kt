package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.PortfolioStrategyLink

interface StrategiesRepository {
    suspend fun listPortfolioStrategies(portfolioId: String): Result<List<PortfolioStrategyLink>>
}
