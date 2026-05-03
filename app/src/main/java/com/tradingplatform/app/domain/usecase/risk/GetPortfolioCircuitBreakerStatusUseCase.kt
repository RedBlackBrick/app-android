package com.tradingplatform.app.domain.usecase.risk

import com.tradingplatform.app.domain.model.PortfolioCircuitBreakerStatus
import com.tradingplatform.app.domain.repository.RiskRepository
import javax.inject.Inject

/**
 * Returns the current circuit-breaker status for the user's portfolio.
 *
 * The Dashboard renders a small badge from the result:
 *  - hidden if [PortfolioCircuitBreakerStatus.enabled] is false (no rule active),
 *  - green when [PortfolioCircuitBreakerStatus.state] is CLOSED,
 *  - red when state is OPEN (trading currently paused for this portfolio),
 *  - amber/warning when [PortfolioCircuitBreakerStatus.redisUnavailable] is true
 *    (the backend reports state=OPEN to mirror its fail-closed policy).
 */
class GetPortfolioCircuitBreakerStatusUseCase @Inject constructor(
    private val repository: RiskRepository,
) {
    suspend operator fun invoke(portfolioId: String): Result<PortfolioCircuitBreakerStatus> =
        repository.getPortfolioCircuitBreakerStatus(portfolioId)
}
