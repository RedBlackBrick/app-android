package com.tradingplatform.app.domain.usecase.portfolio

import com.tradingplatform.app.domain.model.Portfolio
import com.tradingplatform.app.domain.repository.PortfolioSelectionRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Dernière liste connue des portefeuilles du compte (vide avant le premier
 * [RefreshPortfoliosUseCase]). Alimente le sélecteur de portefeuille et « Mes portefeuilles ».
 */
class ObservePortfoliosUseCase @Inject constructor(
    private val repo: PortfolioSelectionRepository,
) {
    operator fun invoke(): StateFlow<List<Portfolio>> = repo.portfolios
}
