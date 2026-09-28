package com.tradingplatform.app.data.local.datastore

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests JVM de [EncryptedDataStore] (audit #17 — store récupérable).
 *
 * Pas d'Android Keystore sous Robolectric : on injecte via le constructeur interne une
 * factory de prefs en clair (même nom de fichier que la prod, pour que
 * `deleteSharedPreferences` dans [EncryptedDataStore.resetCorruptedStore] l'efface) et un
 * suppresseur d'alias MasterKey factice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EncryptedDataStoreTest {

    private companion object {
        const val PREFS_NAME = "trading_secure_prefs"
    }

    private lateinit var context: Context
    private var masterKeyDeletions = 0

    private val plainFactory: (Context) -> SharedPreferences = { ctx ->
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun store(factory: (Context) -> SharedPreferences = plainFactory) =
        EncryptedDataStore(context, factory) { masterKeyDeletions++ }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteSharedPreferences(PREFS_NAME)
        masterKeyDeletions = 0
    }

    // ── Round-trip ────────────────────────────────────────────────────────────

    @Test
    fun `write then read round-trips string boolean and local token`() = runTest {
        val ds = store()

        assertEquals(SecureReadResult.NotFound, ds.readStringSafe(DataStoreKeys.ACCESS_TOKEN))
        assertEquals(SecureReadResult.NotFound, ds.readBooleanSafe(DataStoreKeys.SETUP_COMPLETED))

        ds.writeString(DataStoreKeys.ACCESS_TOKEN, "tok")
        ds.writeBoolean(DataStoreKeys.SETUP_COMPLETED, true)
        ds.writeLocalToken("dev-1", "lt")

        assertEquals("tok", ds.readString(DataStoreKeys.ACCESS_TOKEN))
        assertEquals(SecureReadResult.Found("tok"), ds.readStringSafe(DataStoreKeys.ACCESS_TOKEN))
        assertEquals(SecureReadResult.Found(true), ds.readBooleanSafe(DataStoreKeys.SETUP_COMPLETED))
        assertEquals(true, ds.readBoolean(DataStoreKeys.SETUP_COMPLETED))
        assertEquals("lt", ds.readLocalToken("dev-1"))
    }

    @Test
    fun `clearSession removes session keys but preserves WG and setup keys`() = runTest {
        val ds = store()
        ds.writeString(DataStoreKeys.ACCESS_TOKEN, "tok")
        ds.saveCookie("refresh_token", "rt")
        ds.writeString(DataStoreKeys.WG_PRIVATE_KEY, "wgkey")
        ds.writeString(DataStoreKeys.WG_ENDPOINT, "vps:51820")
        ds.writeString(DataStoreKeys.WG_ALLOWED_IPS, "10.42.0.0/24")
        ds.writeBoolean(DataStoreKeys.SETUP_COMPLETED, true)
        ds.writeLocalToken("dev-1", "lt")

        ds.clearSession()

        assertEquals(SecureReadResult.NotFound, ds.readStringSafe(DataStoreKeys.ACCESS_TOKEN))
        assertTrue(ds.loadCookies().isEmpty())
        assertEquals("wgkey", ds.readString(DataStoreKeys.WG_PRIVATE_KEY))
        assertEquals("vps:51820", ds.readString(DataStoreKeys.WG_ENDPOINT))
        assertEquals("10.42.0.0/24", ds.readString(DataStoreKeys.WG_ALLOWED_IPS))
        assertEquals(SecureReadResult.Found(true), ds.readBooleanSafe(DataStoreKeys.SETUP_COMPLETED))
        assertEquals("lt", ds.readLocalToken("dev-1"))
    }

    // ── removeIfEquals (compare-and-remove, PR 4.5 / audit #19) ────────────────

    @Test
    fun `removeIfEquals removes the key when the value is unchanged`() = runTest {
        val ds = store()
        ds.writeString(DataStoreKeys.PENDING_FCM_TOKEN, "tok-1")

        val removed = ds.removeIfEquals(DataStoreKeys.PENDING_FCM_TOKEN, "tok-1")

        assertTrue(removed)
        assertEquals(SecureReadResult.NotFound, ds.readStringSafe(DataStoreKeys.PENDING_FCM_TOKEN))
    }

    @Test
    fun `removeIfEquals keeps the key when the value changed concurrently`() = runTest {
        val ds = store()
        ds.writeString(DataStoreKeys.PENDING_FCM_TOKEN, "tok-1")
        // Simulates a token rotation (onNewToken called again) while a registration
        // attempt for "tok-1" was in flight.
        ds.writeString(DataStoreKeys.PENDING_FCM_TOKEN, "tok-2")

        val removed = ds.removeIfEquals(DataStoreKeys.PENDING_FCM_TOKEN, "tok-1")

        assertFalse(removed)
        assertEquals(SecureReadResult.Found("tok-2"), ds.readStringSafe(DataStoreKeys.PENDING_FCM_TOKEN))
    }

    @Test
    fun `removeIfEquals returns false when the key was never written`() = runTest {
        val ds = store()

        val removed = ds.removeIfEquals(DataStoreKeys.PENDING_FCM_TOKEN, "tok-1")

        assertFalse(removed)
    }

    @Test
    fun `removeIfEquals returns false when the store is unavailable`() = runTest {
        val ds = store { throw IllegalStateException("tink alpha") }

        val removed = ds.removeIfEquals(DataStoreKeys.PENDING_FCM_TOKEN, "tok-1")

        assertFalse(removed)
    }

    // ── Init failure / reset ────────────────────────────────────────────────────

    @Test
    fun `init SecurityException then reset recovers a working store`() = runTest {
        var calls = 0
        val flakyFactory: (Context) -> SharedPreferences = { ctx ->
            calls++
            if (calls == 1) throw SecurityException("Could not decrypt keyset")
            plainFactory(ctx)
        }
        val ds = store(flakyFactory)

        assertTrue(ds.readStringSafe(DataStoreKeys.ACCESS_TOKEN) is SecureReadResult.Corrupted)

        assertTrue(ds.resetCorruptedStore())
        assertEquals(1, masterKeyDeletions)

        ds.writeString(DataStoreKeys.ACCESS_TOKEN, "fresh")
        assertEquals(SecureReadResult.Found("fresh"), ds.readStringSafe(DataStoreKeys.ACCESS_TOKEN))
    }

    @Test
    fun `init IllegalStateException is caught and reported as unavailable`() = runTest {
        val ds = store { throw IllegalStateException("tink alpha") }

        assertNull(ds.readString(DataStoreKeys.ACCESS_TOKEN))
        assertNull(ds.readLocalToken("dev-1"))
        assertTrue(ds.readStringSafe(DataStoreKeys.ACCESS_TOKEN) is SecureReadResult.Corrupted)
        assertTrue(ds.readBooleanSafe(DataStoreKeys.SETUP_COMPLETED) is SecureReadResult.Corrupted)
        // Les écritures sont silencieusement ignorées (pas de crash)
        ds.writeString(DataStoreKeys.ACCESS_TOKEN, "ignored")
    }

    @Test
    fun `reset returns false when the store cannot be recreated`() = runTest {
        val ds = store { throw SecurityException("keystore gone") }

        assertFalse(ds.resetCorruptedStore())
        assertEquals(1, masterKeyDeletions)
        assertTrue(ds.readStringSafe(DataStoreKeys.ACCESS_TOKEN) is SecureReadResult.Corrupted)
    }

    @Test
    fun `reset still recreates the store when master key deletion throws`() = runTest {
        val ds = EncryptedDataStore(context, plainFactory) { throw IllegalStateException("no keystore") }

        assertTrue(ds.resetCorruptedStore())
    }

    @Test
    fun `reset wipes everything including WG config and setup flag`() = runTest {
        val ds = store()
        // Clés critiques → commit synchrone : pas d'apply() en vol pendant la suppression.
        ds.writeString(DataStoreKeys.ACCESS_TOKEN, "tok")
        ds.writeString(DataStoreKeys.WG_PRIVATE_KEY, "wgkey")
        ds.writeBoolean(DataStoreKeys.SETUP_COMPLETED, true)

        assertTrue(ds.resetCorruptedStore())

        assertEquals(SecureReadResult.NotFound, ds.readStringSafe(DataStoreKeys.ACCESS_TOKEN))
        assertEquals(SecureReadResult.NotFound, ds.readStringSafe(DataStoreKeys.WG_PRIVATE_KEY))
        assertEquals(SecureReadResult.NotFound, ds.readBooleanSafe(DataStoreKeys.SETUP_COMPLETED))
    }

    // ── Read-time SecurityException (security-crypto alpha decrypt failure) ───

    @Test
    fun `SecurityException on read maps to Corrupted or null`() = runTest {
        val brokenPrefs = mockk<SharedPreferences> {
            every { getString(any(), any()) } throws SecurityException("Could not decrypt value")
            every { contains(any()) } throws SecurityException("Could not decrypt key")
        }
        val ds = store { brokenPrefs }

        assertTrue(ds.readStringSafe(DataStoreKeys.ACCESS_TOKEN) is SecureReadResult.Corrupted)
        assertTrue(ds.readBooleanSafe(DataStoreKeys.SETUP_COMPLETED) is SecureReadResult.Corrupted)
        assertNull(ds.readString(DataStoreKeys.ACCESS_TOKEN))
        assertNull(ds.readString("device_wg_pubkey_1"))
        assertNull(ds.readLocalToken("dev-1"))
        assertNull(ds.readBoolean(DataStoreKeys.SETUP_COMPLETED))
    }
}
