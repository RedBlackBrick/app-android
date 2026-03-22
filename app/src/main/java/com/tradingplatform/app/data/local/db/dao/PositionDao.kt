package com.tradingplatform.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.tradingplatform.app.data.local.db.entity.PositionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PositionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(positions: List<PositionEntity>)

    @Query("SELECT * FROM positions ORDER BY symbol ASC")
    fun getAllFlow(): Flow<List<PositionEntity>>

    @Query("SELECT * FROM positions ORDER BY symbol ASC")
    suspend fun getAll(): List<PositionEntity>

    @Query("SELECT * FROM positions WHERE id = :positionId LIMIT 1")
    suspend fun getById(positionId: Int): PositionEntity?

    @Query("DELETE FROM positions WHERE synced_at < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long)

    @Query("DELETE FROM positions")
    suspend fun deleteAll()

    /**
     * Upsert + purge en une seule transaction Room.
     * Garantit qu'aucune lecture concurrente ne voit un état intermédiaire
     * (positions insérées mais anciennes pas encore purgées, ou inversement).
     *
     * WAL mode (défaut Room) : les lectures UI via [getAllFlow] ne sont pas
     * bloquées par cette transaction — elles lisent le snapshot pré-transaction.
     */
    @Transaction
    suspend fun upsertAllAndPurge(positions: List<PositionEntity>, cutoffMillis: Long) {
        upsertAll(positions)
        deleteOlderThan(cutoffMillis)
    }
}
