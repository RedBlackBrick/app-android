package com.tradingplatform.app.domain.usecase.portfolio

import com.tradingplatform.app.domain.model.Transaction
import com.tradingplatform.app.domain.repository.PortfolioRepository
import javax.inject.Inject

class GetTransactionsUseCase @Inject constructor(
    private val repository: PortfolioRepository,
) {
    suspend operator fun invoke(
        portfolioId: Int,
        limit: Int = 50,
        offset: Int = 0,
        symbol: String? = null,
    ): Result<List<Transaction>> =
        repository.getTransactions(portfolioId, limit, offset, symbol)
}
