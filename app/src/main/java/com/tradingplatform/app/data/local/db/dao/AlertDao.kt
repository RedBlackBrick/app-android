package com.tradingplatform.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tradingplatform.app.data.local.db.entity.AlertEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlertDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alert: AlertEntity)

    @Query("SELECT * FROM alerts ORDER BY received_at DESC")
    fun getAllFlow(): Flow<List<AlertEntity>>

    @Query("SELECT * FROM alerts ORDER BY received_at DESC")
    suspend fun getAll(): List<AlertEntity>

    @Query("UPDATE alerts SET read = 1 WHERE id = :alertId")
    suspend fun markRead(alertId: Long)

    /** Purge alertes > 30 jours */
    @Query("DELETE FROM alerts WHERE received_at < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long)

    /** Garde seulement les 500 dernières alertes */
    @Query("""
        DELETE FROM alerts WHERE id NOT IN (
            SELECT id FROM alerts ORDER BY received_at DESC LIMIT 500
        )
    """)
    suspend fun keepOnlyLatest500()

    @Query("SELECT COUNT(*) FROM alerts")
    suspend fun count(): Int
}
