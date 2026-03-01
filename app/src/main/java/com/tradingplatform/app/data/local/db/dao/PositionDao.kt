package com.tradingplatform.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Query("DELETE FROM positions WHERE synced_at < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long)

    @Query("DELETE FROM positions")
    suspend fun deleteAll()
}
