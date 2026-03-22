package com.tradingplatform.app.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Manages the user's watchlist of symbols, persisted locally via Room.
 *
 * The watchlist is local-only — it is not synchronized with the VPS.
 * Symbols are stored as strings and used to subscribe to quote updates.
 */
interface WatchlistRepository {
    fun getWatchlist(): Flow<List<String>>
    suspend fun addSymbol(symbol: String): Result<Unit>
    suspend fun removeSymbol(symbol: String): Result<Unit>
}
