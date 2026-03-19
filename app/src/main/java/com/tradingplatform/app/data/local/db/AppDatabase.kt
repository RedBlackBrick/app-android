package com.tradingplatform.app.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tradingplatform.app.data.local.db.dao.AlertDao
import com.tradingplatform.app.data.local.db.dao.DeviceDao
import com.tradingplatform.app.data.local.db.dao.PnlDao
import com.tradingplatform.app.data.local.db.dao.PositionDao
import com.tradingplatform.app.data.local.db.dao.QuoteDao
import com.tradingplatform.app.data.local.db.entity.AlertEntity
import com.tradingplatform.app.data.local.db.entity.DeviceEntity
import com.tradingplatform.app.data.local.db.entity.PnlSnapshotEntity
import com.tradingplatform.app.data.local.db.entity.PositionEntity
import com.tradingplatform.app.data.local.db.entity.QuoteEntity

// ── Migration 2 → 3 : ajout des colonnes de métriques hardware Radxa ─────────
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE devices ADD COLUMN cpu_pct REAL")
        database.execSQL("ALTER TABLE devices ADD COLUMN memory_pct REAL")
        database.execSQL("ALTER TABLE devices ADD COLUMN temperature REAL")
        database.execSQL("ALTER TABLE devices ADD COLUMN disk_pct REAL")
        database.execSQL("ALTER TABLE devices ADD COLUMN uptime_seconds INTEGER")
        database.execSQL("ALTER TABLE devices ADD COLUMN firmware_version TEXT")
        database.execSQL("ALTER TABLE devices ADD COLUMN hostname TEXT")
    }
}

@Database(
    entities = [
        PositionEntity::class,
        PnlSnapshotEntity::class,
        AlertEntity::class,
        DeviceEntity::class,
        QuoteEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun positionDao(): PositionDao
    abstract fun pnlDao(): PnlDao
    abstract fun alertDao(): AlertDao
    abstract fun deviceDao(): DeviceDao
    abstract fun quoteDao(): QuoteDao
}
