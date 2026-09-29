package com.tradingplatform.app.domain.usecase.portfolio

import com.tradingplatform.app.domain.model.PortfolioStrategyEntry
import com.tradingplatform.app.domain.repository.StrategiesRepository
import javax.inject.Inject

/**
 * Liste les stratégies d'un portefeuille (liens actifs ET en pause) avec leur nom. Le nom est
 * `null` quand le catalogue ne le connaît pas. Complète [GetActiveStrategyCountUseCase], conservé
 * pour la tuile « Stratégies actives » du Dashboard.
 */
class GetPortfolioStrategiesUseCase @Inject constructor(
    private val repository: StrategiesRepository,
) {
    suspend operator fun invoke(portfolioId: String): Result<List<PortfolioStrategyEntry>> =
        repository.listPortfolioStrategyEntries(portfolioId)
}
