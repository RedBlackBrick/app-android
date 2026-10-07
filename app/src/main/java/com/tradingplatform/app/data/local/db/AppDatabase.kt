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
import com.tradingplatform.app.data.local.db.dao.VigieDao
import com.tradingplatform.app.data.local.db.dao.WatchlistDao
import com.tradingplatform.app.data.local.db.entity.AlertEntity
import com.tradingplatform.app.data.local.db.entity.DeviceEntity
import com.tradingplatform.app.data.local.db.entity.PnlSnapshotEntity
import com.tradingplatform.app.data.local.db.entity.PositionEntity
import com.tradingplatform.app.data.local.db.entity.QuoteEntity
import com.tradingplatform.app.data.local.db.entity.VigieSnapshotEntity
import com.tradingplatform.app.data.local.db.entity.WatchlistEntity

// ═══════════════════════════════════════════════════════════════════════════════
// HISTORIQUE DES VERSIONS DE SCHÉMA
// ───────────────────────────────────────────────────────────────────────────────
// v7 = baseline du premier release (2026-09) — l'app n'a jamais été livrée avant
// cette version, donc aucune migration n'existe (et n'a besoin d'exister) avant
// elle. Les anciens schémas 1.json…6.json et les MIGRATION_* correspondants ont
// été supprimés (PR 1.6, finding #5, décision D2) : ils décrivaient des états de
// schéma que plus aucun device n'a jamais eu. 7.json est le seul schéma exporté
// et sert de référence pour SchemaBaselineTest / MigrationTest.
//
// STRATÉGIE DE MIGRATION — RÈGLES IMPÉRATIVES
// ───────────────────────────────────────────────────────────────────────────────
// DEBUG / DEV   : fallbackToDestructiveMigration() acceptable (schéma instable).
//                 Configuré dans DatabaseModule.kt uniquement pour les builds debug.
//
// RELEASE       : pas de fallback par design — la baseline v7 ne cible que les
//                 installations fraîches (versionCode 1, aucun utilisateur en v1-6).
//                 PREMIER CHANGEMENT DE SCHÉMA POST-RELEASE (v8+) : à partir de là,
//                 les migrations explicites redeviennent obligatoires pour ne pas
//                 perdre les données utilisateur (alerts notamment, pas de backup
//                 serveur) :
//
// AJOUTER UNE MIGRATION (checklist, applicable à partir de v7 → v8)
// ───────────────────────────────────────────────────────────────────────────────
// 1. Incrémenter `version` dans l'annotation @Database ci-dessous (7 → 8)
// 2. Déclarer val MIGRATION_7_8 = object : Migration(7, 8) { ... } dans ce fichier
// 3. Ajouter .addMigrations(MIGRATION_7_8) dans DatabaseModule.kt (build release)
// 4. Vérifier que exportSchema = true et que le fichier de schéma JSON généré
//    dans app/schemas/ est commité (8.json) — il sert de référence pour les tests
//    de migration
// 5. Écrire une étape de test Room dans MigrationTest (androidTest) qui exerce le
//    chemin 7→8, et mettre à jour SchemaBaselineTest si nécessaire
//
// v8 = ajout de la table `vigie_snapshot` (Vigie « Refuge », ligne unique) — PREMIÈRE migration
// explicite (MIGRATION_7_8 ci-dessous, enregistrée dans DatabaseModule.kt). Les builds release
// n'ont PAS de fallbackToDestructiveMigration : un oubli de migration = crash explicite au boot.
//
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * v7 → v8 : crée `vigie_snapshot` (cache de la Vigie, une ligne `id = 1`) et son index
 * `synced_at`. Purement additive — aucune table existante n'est touchée, donc aucune donnée
 * (alertes locales notamment) n'est perdue. Le SQL doit rester IDENTIQUE à ce que Room attend de
 * [VigieSnapshotEntity] (validé par `MigrationTest.migrate_7_to_8` contre `8.json`).
 */
val MIGRATION_7_8: Migration = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `vigie_snapshot` (" +
                "`id` INTEGER NOT NULL, " +
                "`as_of` TEXT NOT NULL, " +
                "`computed_at` INTEGER, " +
                "`score` REAL, " +
                "`level` TEXT NOT NULL, " +
                "`level_raw` TEXT NOT NULL, " +
                "`refuge_state` TEXT NOT NULL, " +
                "`coverage` REAL, " +
                "`live_indicators` INTEGER NOT NULL, " +
                "`expected_indicators` INTEGER NOT NULL, " +
                "`most_stressed_family_code` TEXT, " +
                "`most_stressed_family_label` TEXT, " +
                "`most_stressed_family_score` REAL, " +
                "`is_stale` INTEGER NOT NULL, " +
                "`payload_json` TEXT NOT NULL, " +
                "`synced_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_vigie_snapshot_synced_at` " +
                "ON `vigie_snapshot` (`synced_at`)",
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
        VigieSnapshotEntity::class,
    ],
    // v7 = baseline (PnlSnapshotEntity reshaped on /pnl y est incluse — audit PR 2.2) ; v8 = vigie_snapshot.
    version = 8,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun positionDao(): PositionDao
    abstract fun pnlDao(): PnlDao
    abstract fun alertDao(): AlertDao
    abstract fun deviceDao(): DeviceDao
    abstract fun quoteDao(): QuoteDao
    abstract fun watchlistDao(): WatchlistDao
    abstract fun vigieDao(): VigieDao
}
