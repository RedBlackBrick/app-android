package com.tradingplatform.app.domain.usecase.market

import com.tradingplatform.app.domain.repository.WatchlistRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetWatchlistUseCase @Inject constructor(
    private val repository: WatchlistRepository,
) {
    operator fun invoke(): Flow<List<String>> =
        repository.getWatchlist()
}
