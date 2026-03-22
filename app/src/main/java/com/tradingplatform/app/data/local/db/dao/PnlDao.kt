package com.tradingplatform.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.tradingplatform.app.data.local.db.entity.PnlSnapshotEntity

@Dao
interface PnlDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: PnlSnapshotEntity)

    @Query("SELECT * FROM pnl_snapshots WHERE period = :period ORDER BY synced_at DESC LIMIT 1")
    suspend fun getLatestByPeriod(period: String): PnlSnapshotEntity?

    @Query("DELETE FROM pnl_snapshots WHERE synced_at < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long)

    @Query("DELETE FROM pnl_snapshots")
    suspend fun deleteAll()

    /**
     * Upsert + purge en une seule transaction Room.
     * Garantit l'atomicité : pas d'état intermédiaire visible par les lecteurs.
     */
    @Transaction
    suspend fun upsertAndPurge(snapshot: PnlSnapshotEntity, cutoffMillis: Long) {
        upsert(snapshot)
        deleteOlderThan(cutoffMillis)
    }
}
