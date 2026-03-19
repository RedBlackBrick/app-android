package com.tradingplatform.app.domain.usecase.portfolio

import com.tradingplatform.app.domain.model.Position
import com.tradingplatform.app.domain.repository.PortfolioRepository
import javax.inject.Inject

class GetPositionUseCase @Inject constructor(
    private val repository: PortfolioRepository,
) {
    suspend operator fun invoke(
        portfolioId: String,
        positionId: Int,
    ): Result<Position> =
        repository.getPosition(portfolioId, positionId)
}
