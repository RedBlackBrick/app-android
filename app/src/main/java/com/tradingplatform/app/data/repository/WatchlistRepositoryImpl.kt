package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.local.db.dao.WatchlistDao
import com.tradingplatform.app.data.local.db.entity.WatchlistEntity
import com.tradingplatform.app.domain.repository.WatchlistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WatchlistRepositoryImpl @Inject constructor(
    private val watchlistDao: WatchlistDao,
) : WatchlistRepository {

    override fun getWatchlist(): Flow<List<String>> =
        watchlistDao.getAllFlow().map { entities ->
            entities.map { it.symbol }
        }

    override suspend fun addSymbol(symbol: String): Result<Unit> = runCatching {
        watchlistDao.insert(WatchlistEntity(symbol = symbol.uppercase()))
    }

    override suspend fun removeSymbol(symbol: String): Result<Unit> = runCatching {
        watchlistDao.delete(symbol.uppercase())
    }
}
