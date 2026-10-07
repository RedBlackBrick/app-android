package com.tradingplatform.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.tradingplatform.app.data.local.db.entity.VigieSnapshotEntity

/** DAO `vigie_snapshot` — ligne unique (`id = 1`), upsert par REPLACE. */
@Dao
interface VigieDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: VigieSnapshotEntity)

    @Query("SELECT * FROM vigie_snapshot WHERE id = 1")
    suspend fun get(): VigieSnapshotEntity?

    @Query("DELETE FROM vigie_snapshot WHERE synced_at < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long)

    @Query("DELETE FROM vigie_snapshot")
    suspend fun deleteAll()

    /**
     * Upsert + purge en UNE transaction, APRÈS une sync réussie seulement (CLAUDE.md §2 politique de
     * rétention). Sur une ligne unique la purge ne retire que les restes d'un schéma antérieur ;
     * elle existe pour appliquer la même règle que les autres tables, jamais pour vider la ligne
     * qu'on vient d'écrire (`synced_at` = maintenant).
     */
    @Transaction
    suspend fun upsertAndPurge(snapshot: VigieSnapshotEntity, cutoffMillis: Long) {
        upsert(snapshot)
        deleteOlderThan(cutoffMillis)
    }
}
