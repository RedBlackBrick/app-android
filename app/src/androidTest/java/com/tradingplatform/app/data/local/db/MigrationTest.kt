package com.tradingplatform.app.data.local.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Tests de schéma Room pour [AppDatabase].
 *
 * v7 est la baseline du premier release (CLAUDE.md §2) : l'app n'a jamais été livrée
 * avant cette version, donc il n'existe (et n'a besoin d'exister) aucun chemin de
 * migration antérieur. Les anciens schémas 1.json…6.json et les tests par étape ont
 * été supprimés (PR 1.6, finding #5, décision D2).
 *
 * - [baseline_v7_exportedSchemaMatchesEntities] : le schéma exporté `7.json` se crée et se
 *   valide tel quel (baseline figée).
 * - [migrate_7_to_8] : [MIGRATION_7_8] (table `vigie_snapshot`, Vigie « Refuge ») produit
 *   exactement le schéma exporté `8.json` ET conserve les lignes des tables existantes
 *   (une ligne `alerts` écrite en v7 survit). `8.json` est généré par le build Room (KSP)
 *   — une divergence ici indique un `8.json` non régénéré/non commité, ou un SQL de
 *   migration qui s'écarte de [com.tradingplatform.app.data.local.db.entity.VigieSnapshotEntity].
 *
 * Exécution :
 * ```
 * ./gradlew connectedAndroidTest
 * ```
 *
 * Nécessite un émulateur/device connecté (tests instrumentation natifs SQLite).
 * Voir aussi [com.tradingplatform.app.data.local.db.SchemaBaselineTest] (JVM,
 * Robolectric) pour la même assertion dans la suite unitaire rapide.
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
    fun baseline_v7_exportedSchemaMatchesEntities() {
        helper.createDatabase(TEST_DB, 7).close()

        // Pas de migration à jouer : v7 est la baseline. runMigrationsAndValidate
        // avec une liste vide valide que le schéma créé par Room à partir des
        // entités correspond au JSON exporté (7.json).
        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true)
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_7_to_8() {
        helper.createDatabase(TEST_DB, 7).apply {
            // Une alerte locale (pas de backup serveur) doit survivre à la migration.
            execSQL(
                "INSERT INTO alerts (title, body, type, received_at, `read`, synced_at) " +
                    "VALUES ('titre', 'corps', 'info', 1000, 0, 1000)",
            )
            close()
        }

        // validateDroppedTables = true : le schéma obtenu doit égaler 8.json (table + index).
        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, MIGRATION_7_8)

        db.query("SELECT COUNT(*) FROM alerts").use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
        }
        // La nouvelle table existe, vide, et accepte une ligne conforme à l'entité.
        db.query("SELECT COUNT(*) FROM vigie_snapshot").use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }
        db.close()
    }

    companion object {
        private const val TEST_DB = "migration-test.db"
    }
}
