package com.tradingplatform.app.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
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
// ═══════════════════════════════════════════════════════════════════════════════

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
