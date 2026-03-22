package com.tradingplatform.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.tradingplatform.app.data.local.db.entity.QuoteEntity

@Dao
interface QuoteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(quote: QuoteEntity)

    @Query("SELECT * FROM quotes WHERE symbol = :symbol")
    suspend fun getBySymbol(symbol: String): QuoteEntity?

    @Query("SELECT symbol FROM quotes")
    suspend fun getAllSymbols(): List<String>

    @Query("DELETE FROM quotes WHERE synced_at < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long)

    /**
     * Upsert + purge en une seule transaction Room.
     * Garantit l'atomicité : pas d'état intermédiaire visible par les lecteurs.
     */
    @Transaction
    suspend fun upsertAndPurge(quote: QuoteEntity, cutoffMillis: Long) {
        upsert(quote)
        deleteOlderThan(cutoffMillis)
    }
}
