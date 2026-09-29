package com.tradingplatform.app.domain.usecase.portfolio

import com.tradingplatform.app.domain.model.Portfolio
import com.tradingplatform.app.domain.repository.PortfolioSelectionRepository
import javax.inject.Inject

/**
 * Recharge la liste des portefeuilles (`GET /v1/portfolios`) en conservant la sélection valide.
 * Liste vide => échec « No portfolio found ».
 */
class RefreshPortfoliosUseCase @Inject constructor(
    private val repo: PortfolioSelectionRepository,
) {
    suspend operator fun invoke(): Result<List<Portfolio>> = repo.refresh()
}
