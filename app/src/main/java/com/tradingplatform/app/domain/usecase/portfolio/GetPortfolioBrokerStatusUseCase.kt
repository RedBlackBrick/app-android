package com.tradingplatform.app.domain.usecase.portfolio

import com.tradingplatform.app.domain.model.PortfolioBrokerStatus
import com.tradingplatform.app.domain.repository.PortfolioRepository
import javax.inject.Inject

/**
 * Connexion broker d'un portefeuille (lecture seule). `Result.success(null)` = aucune connexion
 * configurée.
 */
class GetPortfolioBrokerStatusUseCase @Inject constructor(
    private val repository: PortfolioRepository,
) {
    suspend operator fun invoke(portfolioId: String): Result<PortfolioBrokerStatus?> =
        repository.getBrokerStatus(portfolioId)
}
