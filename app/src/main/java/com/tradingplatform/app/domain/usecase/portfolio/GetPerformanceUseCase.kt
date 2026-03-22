package com.tradingplatform.app.domain.usecase.portfolio

import com.tradingplatform.app.domain.model.PerformanceMetrics
import com.tradingplatform.app.domain.repository.PortfolioRepository
import javax.inject.Inject

/**
 * Fetches the full set of performance metrics for a portfolio.
 *
 * Delegates to [PortfolioRepository.getPerformance] and propagates [Result]
 * without transformation (no business logic beyond the API call).
 */
class GetPerformanceUseCase @Inject constructor(
    private val repository: PortfolioRepository,
) {
    suspend operator fun invoke(portfolioId: String): Result<PerformanceMetrics> =
        repository.getPerformance(portfolioId)
}
