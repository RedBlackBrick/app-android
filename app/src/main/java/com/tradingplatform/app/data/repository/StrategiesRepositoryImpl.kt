package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.StrategiesApi
import com.tradingplatform.app.domain.model.PortfolioStrategyLink
import com.tradingplatform.app.domain.repository.StrategiesRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StrategiesRepositoryImpl @Inject constructor(
    private val api: StrategiesApi,
) : StrategiesRepository {

    override suspend fun listPortfolioStrategies(
        portfolioId: String,
    ): Result<List<PortfolioStrategyLink>> = runCatching {
        val response = api.listPortfolioStrategies(portfolioId)
        if (!response.isSuccessful) {
            error("List portfolio strategies failed: HTTP ${response.code()}")
        }
        response.body()?.map {
            PortfolioStrategyLink(
                portfolioId = it.portfolioId,
                strategyId = it.strategyId,
                isActive = it.isActive,
            )
        } ?: emptyList()
    }
}
