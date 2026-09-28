package com.tradingplatform.app.data.local.datastore

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented test for [EncryptedDataStore.resetCorruptedStore] (audit finding #17,
 * CLAUDE.md §4 "Corruption EncryptedDataStore" / "Corruption détectée → reset complet").
 * Exercises the real Android Keystore + Tink `EncryptedSharedPreferences` stack — this cannot
 * be simulated under Robolectric (no real Keystore provider), hence androidTest rather than
 * `test/`.
 *
 * Uses the public `EncryptedDataStore(context)` constructor (not the internal test-seam
 * constructor used by the Robolectric unit tests) — the whole point here is to exercise the
 * production MasterKey/Keystore path end to end on a real device/emulator.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedDataStoreResetTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // "trading_secure_prefs" mirrors EncryptedDataStore's private PREFS_NAME constant — not
    // exposed publicly, so duplicated here deliberately to assert on the on-disk artifact.
    private val prefsFile: File
        get() = File(context.applicationInfo.dataDir, "shared_prefs/trading_secure_prefs.xml")

    /**
     * The real [com.tradingplatform.app.TradingApplication] runs in the instrumentation process:
     * its Hilt singleton `EncryptedDataStore` (biometric lock persistence at cold start) holds
     * its own `SharedPreferencesImpl` for the same file and may commit to it while this test
     * runs. Let that startup activity settle first — otherwise a late commit from the app's
     * instance can rewrite the file with the old keysets between our delete and recreate
     * (the case `resetCorruptedStore()` now retries on, but this test wants to exercise the
     * nominal reset path deterministically).
     */
    @Before
    fun letAppStartupSettle() {
        Thread.sleep(APP_STARTUP_SETTLE_MS)
    }

    @Test
    fun resetCorruptedStore_wipesOldData_thenStoreIsUsableAgainWithAFreshKeyset() = runBlocking {
        val store = EncryptedDataStore(context)

        // 1. Write something so the underlying encrypted file actually gets created, and
        //    capture its bytes — used below to prove the reset didn't just `clear()` the same
        //    keyset in place but genuinely deleted+regenerated it.
        store.writeString(DataStoreKeys.WG_ENDPOINT, "vps.example.com:51820")
        assertEquals("vps.example.com:51820", store.readString(DataStoreKeys.WG_ENDPOINT))
        assertTrue("expected the encrypted prefs file to exist after a write", prefsFile.exists())
        val beforeResetBytes = prefsFile.readBytes()

        // 2. Reset — simulates recovery from a corrupted/Keystore-invalidated store
        //    (RecoverFromKeystoreCorruptionUseCase calls this after wiping session/Room/etc).
        val recreated = store.resetCorruptedStore()
        assertTrue("resetCorruptedStore should recreate a usable store", recreated)

        // 3. Nothing survives the reset — both the "silent null" and the safe/disambiguated
        //    read paths agree the old value is gone (not corrupted — genuinely absent).
        assertNull(store.readString(DataStoreKeys.WG_ENDPOINT))
        val safeRead = store.readStringSafe(DataStoreKeys.WG_ENDPOINT)
        assertTrue("expected NotFound after reset, got $safeRead", safeRead is SecureReadResult.NotFound)

        // 4. The file was genuinely deleted and regenerated with a fresh Tink keyset, not just
        //    cleared in place: EncryptedSharedPreferences.create() persists a brand-new keyset
        //    synchronously as part of recreating the store, so by the time
        //    resetCorruptedStore() returns the file exists again — but with different bytes
        //    (new MasterKey-wrapped keyset, no trace of the old encrypted value).
        assertTrue("expected the store to recreate its prefs file", prefsFile.exists())
        val afterResetBytes = prefsFile.readBytes()
        assertTrue(
            "expected the prefs file content to change after reset (new keyset, old data wiped)",
            !beforeResetBytes.contentEquals(afterResetBytes),
        )

        // 5. Store is fully usable again on a fresh MasterKey alias + prefs file.
        store.writeString(DataStoreKeys.WG_ENDPOINT, "vps2.example.com:51820")
        assertEquals("vps2.example.com:51820", store.readString(DataStoreKeys.WG_ENDPOINT))
    }

    private companion object {
        const val APP_STARTUP_SETTLE_MS = 1_500L
    }
}
