package com.tradingplatform.app.security

import androidx.lifecycle.LifecycleOwner
import app.cash.turbine.test
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.security.BiometricLockManager.Companion.INACTIVITY_POLL_MS
import com.tradingplatform.app.security.BiometricLockManager.Companion.INACTIVITY_TIMEOUT_MS
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BiometricLockManager] — propriétaire unique du verrou d'inactivité (audit #8 + LOW).
 *
 * Horloge injectée = temps virtuel du scheduler de test, polling dans `backgroundScope`
 * (UnconfinedTestDispatcher) : `advance()` fait tourner le poll de 5 s de façon déterministe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BiometricLockManagerTest {

    private val dataStore = mockk<EncryptedDataStore>(relaxed = true)
    private val owner = mockk<LifecycleOwner>(relaxed = true)

    private fun TestScope.newManager() = BiometricLockManager(
        dataStore = dataStore,
        applicationScope = backgroundScope,
        clock = { testScheduler.currentTime },
    )

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun givenPersisted(locked: Boolean?, lastInteractionAt: Long?) {
        coEvery { dataStore.readBoolean(DataStoreKeys.BIOMETRIC_LOCKED) } returns locked
        coEvery { dataStore.readLong(DataStoreKeys.LAST_INTERACTION_AT) } returns lastInteractionAt
    }

    // ── Fail-closed initial state ──────────────────────────────────────────────

    @Test
    fun `starts locked until restore or unlock`() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        assertTrue(manager.isLocked.value)
    }

    // ── Inactivity timeout ─────────────────────────────────────────────────────

    @Test
    fun `locks after the inactivity timeout`() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        manager.unlock()
        manager.onStart(owner)

        manager.isLocked.test {
            assertFalse(awaitItem())
            advance(INACTIVITY_TIMEOUT_MS - 1)
            expectNoEvents()
            advance(1)
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        coVerify { dataStore.writeBoolean(DataStoreKeys.BIOMETRIC_LOCKED, true) }
    }

    @Test
    fun `user interaction defers the lock`() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        manager.unlock()
        manager.onStart(owner)

        advance(200_000)
        manager.onUserInteraction() // last = 200 s

        advance(100_000) // t = 300 s — would have locked without the interaction
        assertFalse(manager.isLocked.value)

        advance(INACTIVITY_TIMEOUT_MS - 100_000 - 1) // t = 499.999 s
        assertFalse(manager.isLocked.value)

        advance(1) // t = 500 s = last + timeout
        assertTrue(manager.isLocked.value)
    }

    @Test
    fun `unlock re-arms the timer and locks again after a second timeout`() =
        runTest(UnconfinedTestDispatcher()) {
            // Régression #8 : l'ancien timer de MainActivity ne re-verrouillait plus jamais
            // après le premier déverrouillage (isBiometricLocked jamais remis à false).
            val manager = newManager()
            manager.unlock()
            manager.onStart(owner)

            manager.isLocked.test {
                assertFalse(awaitItem())

                advance(INACTIVITY_TIMEOUT_MS)
                assertTrue(awaitItem())

                manager.unlock()
                assertFalse(awaitItem())

                advance(INACTIVITY_TIMEOUT_MS - 1)
                expectNoEvents()

                advance(1)
                assertTrue(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `inactivity does not lock without an authenticated session`() = runTest(UnconfinedTestDispatcher()) {
        // Setup / Login ne sont pas protégés par le verrou.
        var session = false
        val manager = BiometricLockManager(
            dataStore = dataStore,
            applicationScope = backgroundScope,
            clock = { testScheduler.currentTime },
            hasSession = { session },
        )
        manager.unlock()
        manager.onStart(owner)

        advance(2 * INACTIVITY_TIMEOUT_MS)
        assertFalse(manager.isLocked.value)

        // Une fois la session ouverte, le délai écoulé s'applique au prochain poll.
        session = true
        advance(INACTIVITY_POLL_MS)
        assertTrue(manager.isLocked.value)
    }

    @Test
    fun `touches are ignored while locked`() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        manager.unlock() // last = 0
        manager.onStart(owner)
        advance(INACTIVITY_TIMEOUT_MS)
        assertTrue(manager.isLocked.value)

        advance(10_000)
        manager.onUserInteraction() // must not refresh the timestamp nor unlock
        assertTrue(manager.isLocked.value)

        manager.onStop(owner)
        coVerify { dataStore.writeLong(DataStoreKeys.LAST_INTERACTION_AT, 0L) }
        coVerify(exactly = 0) {
            dataStore.writeLong(DataStoreKeys.LAST_INTERACTION_AT, INACTIVITY_TIMEOUT_MS + 10_000)
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Test
    fun `onStop persists lock state and last interaction`() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        manager.unlock()
        manager.onStart(owner)
        advance(10_000)
        manager.onUserInteraction()

        manager.onStop(owner)

        coVerify { dataStore.writeBoolean(DataStoreKeys.BIOMETRIC_LOCKED, false) }
        coVerify { dataStore.writeLong(DataStoreKeys.LAST_INTERACTION_AT, 10_000L) }
    }

    @Test
    fun `background stops polling and onStart locks if the timeout elapsed meanwhile`() =
        runTest(UnconfinedTestDispatcher()) {
            val manager = newManager()
            manager.unlock()
            manager.onStart(owner)
            manager.onStop(owner)

            advance(INACTIVITY_TIMEOUT_MS + 10 * INACTIVITY_POLL_MS)
            // Pas de polling en arrière-plan : l'état n'est réévalué qu'au retour au premier plan.
            assertFalse(manager.isLocked.value)

            manager.onStart(owner)
            assertTrue(manager.isLocked.value)
        }

    @Test
    fun `onStart within the timeout keeps the app unlocked`() = runTest(UnconfinedTestDispatcher()) {
        val manager = newManager()
        manager.unlock()
        manager.onStart(owner)
        manager.onStop(owner)

        advance(60_000)
        manager.onStart(owner)

        assertFalse(manager.isLocked.value)
    }

    // ── Restore matrix (cold start with a session) ─────────────────────────────

    @Test
    fun `restore unlocked with an old interaction locks`() = runTest(UnconfinedTestDispatcher()) {
        advance(1_000_000)
        givenPersisted(locked = false, lastInteractionAt = testScheduler.currentTime - INACTIVITY_TIMEOUT_MS - 1)
        val manager = newManager()

        manager.restorePersistedState()

        assertTrue(manager.isLocked.value)
    }

    @Test
    fun `restore unlocked with a fresh interaction stays unlocked`() = runTest(UnconfinedTestDispatcher()) {
        advance(1_000_000)
        givenPersisted(locked = false, lastInteractionAt = testScheduler.currentTime - 60_000)
        val manager = newManager()

        manager.restorePersistedState()

        assertFalse(manager.isLocked.value)

        // L'horloge restaurée est bien celle de la dernière interaction : verrou 4 min plus tard.
        manager.onStart(owner)
        advance(INACTIVITY_TIMEOUT_MS - 60_000)
        assertTrue(manager.isLocked.value)
    }

    @Test
    fun `restore with nothing persisted locks`() = runTest(UnconfinedTestDispatcher()) {
        advance(1_000_000)
        givenPersisted(locked = null, lastInteractionAt = null)
        val manager = newManager()

        manager.restorePersistedState()

        assertTrue(manager.isLocked.value)
    }

    @Test
    fun `restore locked with a fresh interaction stays locked`() = runTest(UnconfinedTestDispatcher()) {
        advance(1_000_000)
        givenPersisted(locked = true, lastInteractionAt = testScheduler.currentTime - 1_000)
        val manager = newManager()

        manager.restorePersistedState()

        assertTrue(manager.isLocked.value)
    }

    @Test
    fun `restore does not override an explicit unlock`() = runTest(UnconfinedTestDispatcher()) {
        givenPersisted(locked = true, lastInteractionAt = 0L)
        val manager = newManager()
        manager.unlock()

        manager.restorePersistedState()

        assertFalse(manager.isLocked.value)
    }
}
