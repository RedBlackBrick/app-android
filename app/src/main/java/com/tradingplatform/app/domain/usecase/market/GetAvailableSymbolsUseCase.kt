package com.tradingplatform.app.domain.usecase.market

import com.tradingplatform.app.domain.repository.MarketDataRepository
import javax.inject.Inject

class GetAvailableSymbolsUseCase @Inject constructor(
    private val repository: MarketDataRepository,
) {
    suspend operator fun invoke(): Result<List<String>> =
        repository.getAvailableSymbols()
}
