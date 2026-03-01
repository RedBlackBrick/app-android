package com.tradingplatform.app.domain.usecase.portfolio

import com.tradingplatform.app.domain.model.NavSummary
import com.tradingplatform.app.domain.repository.PortfolioRepository
import javax.inject.Inject

class GetPortfolioNavUseCase @Inject constructor(
    private val repository: PortfolioRepository,
) {
    suspend operator fun invoke(portfolioId: Int): Result<NavSummary> =
        repository.getNav(portfolioId)
}
