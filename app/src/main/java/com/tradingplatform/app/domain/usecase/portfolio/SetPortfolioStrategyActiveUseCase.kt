package com.tradingplatform.app.domain.usecase.portfolio

import com.tradingplatform.app.domain.model.WriteOutcome
import com.tradingplatform.app.domain.repository.StrategiesRepository
import javax.inject.Inject

/**
 * Met en pause (`active = false`) ou reprend (`active = true`) le lien d'une stratégie avec un
 * portefeuille. Écriture : jamais rejouée ; [WriteOutcome.REQUESTED_UNCONFIRMED] = « demandé,
 * état à relire ». Ne concerne que le lien portefeuille-stratégie — jamais la stratégie elle-même.
 */
class SetPortfolioStrategyActiveUseCase @Inject constructor(
    private val repository: StrategiesRepository,
) {
    suspend operator fun invoke(
        portfolioId: String,
        strategyId: String,
        active: Boolean,
    ): Result<WriteOutcome> = repository.setPortfolioStrategyActive(portfolioId, strategyId, active)
}
