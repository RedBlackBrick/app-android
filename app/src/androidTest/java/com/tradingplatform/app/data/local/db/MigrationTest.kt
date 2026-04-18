package com.tradingplatform.app.data.local.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Tests de migration Room pour [AppDatabase].
 *
 * Exerce chaque chemin de migration déclaré dans [AppDatabase] contre les schémas JSON
 * versionnés dans `app/schemas/`. Un échec ici signifie qu'une release bump détruirait
 * le cache local (alertes FCM non-récupérables côté serveur — voir CLAUDE.md §2).
 *
 * Les schémas 1.json → 7.json sont le contrat : toute modification d'entité doit
 * incrémenter la version + générer un nouveau JSON (via `room.schemaDirectory`).
 *
 * Exécution :
 * ```
 * ./gradlew connectedAndroidTest
 * ```
 *
 * Nécessite un émulateur/device connecté (tests instrumentation natifs SQLite).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    @Throws(IOException::class)
    fun migrate2To3_addsHardwareMetricColumns() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            // Insère une ligne avec le schéma v2 (pas de colonnes hardware metrics)
            db.execSQL(
                "INSERT INTO devices (id, name, status, wg_ip, last_heartbeat, synced_at) " +
                    "VALUES ('dev-1', 'Radxa-A', 'online', '10.42.0.5', 1700000000000, 1700000000000)",
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)

        db.query("SELECT id, cpu_pct, memory_pct, temperature, disk_pct, uptime_seconds, firmware_version, hostname FROM devices").use { cursor ->
            check(cursor.moveToFirst()) { "Ligne devices introuvable après MIGRATION_2_3" }
            check(cursor.getString(0) == "dev-1") { "device_id non préservé" }
            for (col in 1..5) {
                check(cursor.isNull(col)) { "Colonne $col doit être NULL (nouvelle, sans default)" }
            }
        }
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate3To4_addsSyncedAtIndexes() {
        helper.createDatabase(TEST_DB, 3).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)

        // Vérifier que tous les index attendus sont créés
        val expectedIndexes = setOf(
            "index_positions_synced_at",
            "index_quotes_synced_at",
            "index_alerts_synced_at",
            "index_alerts_received_at",
            "index_devices_synced_at",
            "index_pnl_snapshots_synced_at",
        )
        val foundIndexes = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'index_%_at'").use { cursor ->
            while (cursor.moveToNext()) {
                foundIndexes.add(cursor.getString(0))
            }
        }
        val missing = expectedIndexes - foundIndexes
        check(missing.isEmpty()) { "Index manquants après MIGRATION_3_4 : $missing" }
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate4To5_addsWatchlistTable() {
        helper.createDatabase(TEST_DB, 4).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5)

        // La table watchlist doit exister et accepter un INSERT
        db.execSQL("INSERT INTO watchlist (symbol, added_at) VALUES ('AAPL', 1700000000000)")
        db.query("SELECT symbol, added_at FROM watchlist WHERE symbol = 'AAPL'").use { cursor ->
            check(cursor.moveToFirst()) { "watchlist.AAPL introuvable après MIGRATION_4_5" }
            check(cursor.getString(0) == "AAPL")
            check(cursor.getLong(1) == 1700000000000L)
        }
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate5To6_addsBrokerGatewayColumns() {
        helper.createDatabase(TEST_DB, 5).use { db ->
            db.execSQL(
                "INSERT INTO devices (id, name, status, wg_ip, last_heartbeat, synced_at) " +
                    "VALUES ('dev-b', 'Radxa-B', 'online', '10.42.0.6', 1700000000000, 1700000000000)",
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, MIGRATION_5_6)

        db.query("SELECT broker_gateway_enabled, broker_gateway_status, broker_gateway_broker_id FROM devices WHERE id = 'dev-b'").use { cursor ->
            check(cursor.moveToFirst())
            check(cursor.isNull(0) && cursor.isNull(1) && cursor.isNull(2)) {
                "Nouvelles colonnes broker_gateway_* doivent être NULL par défaut"
            }
        }
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate6To7_addsSourceQualityAndAvailableMemoryColumns() {
        helper.createDatabase(TEST_DB, 6).use { db ->
            // Insérer une ligne quotes et une ligne devices avec le schéma v6 (6 colonnes de base)
            db.execSQL(
                "INSERT INTO quotes (symbol, price, change_percent, volume, synced_at) " +
                    "VALUES ('AAPL', '185.50', 1.23, 12345678, 1700000000000)",
            )
            db.execSQL(
                "INSERT INTO devices (id, name, status, wg_ip, last_heartbeat, synced_at) " +
                    "VALUES ('dev-c', 'Radxa-C', 'online', '10.42.0.7', 1700000000000, 1700000000000)",
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7)

        db.query("SELECT source_name, source_type, quality, data_mode FROM quotes WHERE symbol = 'AAPL'").use { cursor ->
            check(cursor.moveToFirst())
            for (col in 0..3) {
                check(cursor.isNull(col)) { "quotes.$col doit être NULL après MIGRATION_6_7" }
            }
        }
        db.query("SELECT available_memory_mb FROM devices WHERE id = 'dev-c'").use { cursor ->
            check(cursor.moveToFirst())
            check(cursor.isNull(0)) { "devices.available_memory_mb doit être NULL par défaut" }
        }
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrateAll_v2ToLatest_preservesData() {
        // Scénario de chaîne complète : une app installée en v2 qui upgrade vers la
        // dernière version doit conserver ses données utilisateur (alertes FCM en particulier,
        // non récupérables côté serveur).
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL(
                "INSERT INTO alerts (id, title, body, type, received_at, read) " +
                    "VALUES (42, 'T', 'Body', 'ORDER_FILLED', 1700000000000, 0)",
            )
        }

        // Ouvrir via Room en appliquant TOUTES les migrations déclarées
        Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            TEST_DB,
        )
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .build()
            .apply {
                openHelper.writableDatabase.query("SELECT id, title FROM alerts WHERE id = 42").use { cursor ->
                    check(cursor.moveToFirst()) { "Alerte id=42 perdue pendant la chaîne de migrations" }
                    check(cursor.getString(1) == "T") { "Titre alerte non préservé" }
                }
                close()
            }
    }

    companion object {
        private const val TEST_DB = "migration-test.db"
    }
}
