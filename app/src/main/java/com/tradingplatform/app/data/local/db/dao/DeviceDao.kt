package com.tradingplatform.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tradingplatform.app.data.local.db.entity.DeviceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(devices: List<DeviceEntity>)

    @Query("SELECT * FROM devices ORDER BY name ASC")
    fun getAllFlow(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices ORDER BY name ASC")
    suspend fun getAll(): List<DeviceEntity>

    @Query("SELECT * FROM devices WHERE id = :deviceId")
    suspend fun getById(deviceId: String): DeviceEntity?

    @Query("DELETE FROM devices WHERE synced_at < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long)
}
