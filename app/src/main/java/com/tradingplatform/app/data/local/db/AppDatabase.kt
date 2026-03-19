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

// ═══════════════════════════════════════════════════════════════════════════════
// HISTORIQUE DES VERSIONS DE SCHÉMA
// ───────────────────────────────────────────────────────────────────────────────
// v1 (initial) : tables positions, pnl_snapshots, alerts, devices (colonnes de base), quotes
// v2           : [migration explicite à documenter lors de la bump — voir MIGRATION_1_2]
// v3           : ajout colonnes métriques hardware dans devices
//               (cpu_pct, memory_pct, temperature, disk_pct, uptime_seconds,
//                firmware_version, hostname)
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
