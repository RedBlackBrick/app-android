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
import com.tradingplatform.app.data.local.db.dao.WatchlistDao
import com.tradingplatform.app.data.local.db.entity.AlertEntity
import com.tradingplatform.app.data.local.db.entity.DeviceEntity
import com.tradingplatform.app.data.local.db.entity.PnlSnapshotEntity
import com.tradingplatform.app.data.local.db.entity.PositionEntity
import com.tradingplatform.app.data.local.db.entity.QuoteEntity
import com.tradingplatform.app.data.local.db.entity.WatchlistEntity

// ═══════════════════════════════════════════════════════════════════════════════
// HISTORIQUE DES VERSIONS DE SCHÉMA
// ───────────────────────────────────────────────────────────────────────────────
// v1 (initial) : tables positions, pnl_snapshots, alerts, devices (colonnes de base), quotes
// v2           : [migration explicite à documenter lors de la bump — voir MIGRATION_1_2]
// v3           : ajout colonnes métriques hardware dans devices
//               (cpu_pct, memory_pct, temperature, disk_pct, uptime_seconds,
//                firmware_version, hostname)
// v4           : ajout index sur synced_at (positions, quotes, alerts, devices,
//               pnl_snapshots) et received_at (alerts) pour optimiser les purges
// v5           : ajout table watchlist (symboles favoris de l'utilisateur)
// v6           : ajout colonnes broker gateway dans devices
//               (broker_gateway_enabled, broker_gateway_status, broker_gateway_broker_id)
// v7           : ajout colonnes source quality dans quotes
//               (source_name, source_type, quality, data_mode)
//               + ajout colonne available_memory_mb dans devices
//
// STRATÉGIE DE MIGRATION — RÈGLES IMPÉRATIVES
// ───────────────────────────────────────────────────────────────────────────────
// DEBUG / DEV   : fallbackToDestructiveMigration() acceptable (schéma instable).
//                 Configurer dans DatabaseModule.kt uniquement pour les builds debug.
//
// RELEASE       : OBLIGATOIREMENT addMigrations(MIGRATION_X_Y) dans DatabaseModule.kt.
//                 NE JAMAIS utiliser fallbackToDestructiveMigration() en release :
//                 la table "alerts" (historique FCM local) serait détruite — perte
//                 irrémédiable de données utilisateur (pas de backup serveur).
//
// AJOUTER UNE MIGRATION (checklist)
// ───────────────────────────────────────────────────────────────────────────────
// 1. Incrémenter `version` dans l'annotation @Database ci-dessous (ex : 3 → 4)
// 2. Déclarer val MIGRATION_3_4 = object : Migration(3, 4) { ... } dans ce fichier
// 3. Ajouter .addMigrations(MIGRATION_3_4) dans DatabaseModule.kt (build release)
// 4. Vérifier que exportSchema = true et que le fichier de schéma JSON généré
//    dans app/schemas/ est commité — il sert de référence pour les tests de migration
// 5. Écrire un test Room MigrationTest (androidTest) qui exerce le chemin X→Y
//
// ═══════════════════════════════════════════════════════════════════════════════

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

// ── Migration 3 → 4 : ajout d'index sur synced_at / received_at pour les purges ──
// Les requêtes DELETE ... WHERE synced_at < :cutoff bénéficient d'un index pour éviter
// un full table scan. Overhead en écriture ~5%, négligeable vu la fréquence (1x/5-15 min).
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("CREATE INDEX IF NOT EXISTS index_positions_synced_at ON positions (synced_at)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_quotes_synced_at ON quotes (synced_at)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_alerts_synced_at ON alerts (synced_at)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_alerts_received_at ON alerts (received_at)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_devices_synced_at ON devices (synced_at)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_pnl_snapshots_synced_at ON pnl_snapshots (synced_at)")
    }
}

// ── Migration 5 → 6 : ajout colonnes broker gateway dans devices ────────────
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE devices ADD COLUMN broker_gateway_enabled INTEGER")
        database.execSQL("ALTER TABLE devices ADD COLUMN broker_gateway_status TEXT")
        database.execSQL("ALTER TABLE devices ADD COLUMN broker_gateway_broker_id INTEGER")
    }
}

// ── Migration 6 → 7 : ajout colonnes source quality + device available memory ──
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Quote source quality indicators
        database.execSQL("ALTER TABLE quotes ADD COLUMN source_name TEXT")
        database.execSQL("ALTER TABLE quotes ADD COLUMN source_type TEXT")
        database.execSQL("ALTER TABLE quotes ADD COLUMN quality INTEGER")
        database.execSQL("ALTER TABLE quotes ADD COLUMN data_mode TEXT")
        // Device available memory
        database.execSQL("ALTER TABLE devices ADD COLUMN available_memory_mb INTEGER")
    }
}

// ── Migration 4 → 5 : ajout table watchlist ────────────────────────────────
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS watchlist (" +
                "symbol TEXT NOT NULL PRIMARY KEY, " +
                "added_at INTEGER NOT NULL" +
                ")"
        )
    }
}

@Database(
    entities = [
        PositionEntity::class,
        PnlSnapshotEntity::class,
        AlertEntity::class,
        DeviceEntity::class,
        QuoteEntity::class,
        WatchlistEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun positionDao(): PositionDao
    abstract fun pnlDao(): PnlDao
    abstract fun alertDao(): AlertDao
    abstract fun deviceDao(): DeviceDao
    abstract fun quoteDao(): QuoteDao
    abstract fun watchlistDao(): WatchlistDao
}
