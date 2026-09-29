package com.tradingplatform.app.domain.usecase.portfolio

import com.tradingplatform.app.domain.repository.PortfolioSelectionRepository
import javax.inject.Inject

/**
 * Change le portefeuille actif. Échec si [portfolioId] n'appartient pas à la liste connue ;
 * sélectionner l'id déjà actif est un no-op. Un vrai changement purge les caches Room
 * portfolio-scopés (positions, P&L) avant d'émettre le nouvel id.
 */
class SelectPortfolioUseCase @Inject constructor(
    private val repo: PortfolioSelectionRepository,
) {
    suspend operator fun invoke(portfolioId: String): Result<Unit> = repo.select(portfolioId)
}
