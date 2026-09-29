package com.tradingplatform.app.domain.usecase.portfolio

import com.tradingplatform.app.domain.model.NavCurve
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.repository.PortfolioRepository
import javax.inject.Inject

/** Courbe de NAV (≤ ~120 points, temps croissant) d'un portefeuille sur la [PnlPeriod]. */
class GetNavCurveUseCase @Inject constructor(
    private val repository: PortfolioRepository,
) {
    suspend operator fun invoke(
        portfolioId: String,
        period: PnlPeriod = PnlPeriod.DAY,
    ): Result<NavCurve> =
        repository.getNavCurve(portfolioId, period)
}
