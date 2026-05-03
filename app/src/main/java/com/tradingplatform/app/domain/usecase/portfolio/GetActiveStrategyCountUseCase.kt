package com.tradingplatform.app.domain.usecase.portfolio

import com.tradingplatform.app.domain.repository.StrategiesRepository
import javax.inject.Inject

/**
 * Returns the number of strategies currently flagged ``is_active=true`` for
 * the given portfolio. Backs the "Stratégies actives" tile on the Dashboard.
 *
 * The backend exposes the list directly (no dedicated count endpoint) — at
 * the typical scale (~10 strategies per portfolio) the cost difference is
 * negligible and the route is already cached on the server side
 * (Cache-Control: private, max-age=3).
 */
class GetActiveStrategyCountUseCase @Inject constructor(
    private val repository: StrategiesRepository,
) {
    suspend operator fun invoke(portfolioId: String): Result<Int> =
        repository.listPortfolioStrategies(portfolioId)
            .map { links -> links.count { it.isActive } }
}
