package com.tradingplatform.app.domain.usecase.market

import com.tradingplatform.app.domain.model.Quote
import com.tradingplatform.app.domain.repository.MarketDataRepository
import javax.inject.Inject

class GetQuoteUseCase @Inject constructor(
    private val repository: MarketDataRepository,
) {
    suspend operator fun invoke(symbol: String): Result<Quote> =
        repository.getQuote(symbol)
}
