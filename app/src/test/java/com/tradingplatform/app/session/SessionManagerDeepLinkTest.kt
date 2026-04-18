package com.tradingplatform.app.session

import app.cash.turbine.test
import com.tradingplatform.app.data.session.SessionManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Tests du bus deep link FCM (CLAUDE.md §9).
 *
 * - `notifyDeepLink` émet une destination consommée par [com.tradingplatform.app.ui.navigation.AppNavViewModel]
 * - `replay = 1` garantit qu'un deep link émis avant la composition (cold start depuis notification)
 *   soit délivré au premier subscribe
 * - `clearDeepLink` vide le replay cache pour éviter une re-navigation après rotation
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerDeepLinkTest {

    @Test
    fun `notifyDeepLink emits destination to subscribers`() = runTest {
        val session = SessionManager()
        session.deepLinkEvents.test {
            session.notifyDeepLink("alerts")
            assertEquals("alerts", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deep link emitted before subscription is replayed on subscribe`() = runTest {
        val session = SessionManager()
        // Simule cold start : notification reçue et notifyDeepLink appelé AVANT
        // que AppNavViewModel.deepLinkEvents.collect démarre (replay = 1).
        session.notifyDeepLink("alerts")
        session.deepLinkEvents.test {
            assertEquals("alerts", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `clearDeepLink prevents replay on new subscriber after consumption`() = runTest {
        val session = SessionManager()
        session.notifyDeepLink("alerts")

        // Premier collecteur consomme
        session.deepLinkEvents.test {
            assertEquals("alerts", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        // Efface le replay après consommation (évite re-navigation sur rotation)
        session.clearDeepLink()

        // Nouveau collecteur ne doit PAS recevoir "alerts"
        session.deepLinkEvents.test {
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `multiple notifyDeepLink calls deliver latest to new subscriber`() = runTest {
        val session = SessionManager()
        session.notifyDeepLink("alerts")
        session.notifyDeepLink("dashboard")

        session.deepLinkEvents.test {
            // Replay buffer size = 1 → seul le dernier est re-livré
            assertEquals("dashboard", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
