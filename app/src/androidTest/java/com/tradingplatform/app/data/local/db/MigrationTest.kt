package com.tradingplatform.app.data.local.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Test de baseline Room pour [AppDatabase].
 *
 * v7 est la baseline du premier release (CLAUDE.md §2) : l'app n'a jamais été livrée
 * avant cette version, donc il n'existe (et n'a besoin d'exister) aucun chemin de
 * migration antérieur. Les anciens schémas 1.json…6.json et les tests par étape ont
 * été supprimés (PR 1.6, finding #5, décision D2).
 *
 * Ce test se contente de vérifier que le schéma exporté (`app/schemas/.../7.json`)
 * correspond bien aux entités déclarées dans [AppDatabase] — une divergence ici
 * indique un schéma JSON non régénéré/non commité après un changement d'entité.
 *
 * Premier changement de schéma post-release (v8+) : voir la checklist dans
 * AppDatabase.kt — ajouter MIGRATION_7_8, 8.json, et une étape de migration dans
 * ce fichier qui exerce explicitement le chemin 7→8.
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

    companion object {
        private const val TEST_DB = "migration-test.db"
    }
}
