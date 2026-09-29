package com.tradingplatform.app.ui.navigation

import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.navOptions
import com.tradingplatform.app.ui.components.PortfolioSegment
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Navigation à 4 onglets (Accueil, Portefeuille, Marchés, Alertes) : visibilité de la barre du
 * bas, sélection de l'onglet Portefeuille sur ses trois sections + le détail d'une position, et
 * options de navigation (pas d'empilement de la back stack entre segments).
 */
class TabNavigationTest {

    /** Exécute [action] sur un NavController espion et renvoie (route, options) du `navigate`. */
    private fun navigation(action: NavController.() -> Unit): Pair<String, NavOptions> {
        val navController = mockk<NavController>(relaxed = true)
        navController.action()
        val route = slot<String>()
        val builder = slot<NavOptionsBuilder.() -> Unit>()
        verify(exactly = 1) { navController.navigate(capture(route), capture(builder)) }
        return route.captured to navOptions(builder.captured)
    }

    // ── Barre du bas ────────────────────────────────────────────────────────

    @Test
    fun `bottom bar is shown on the six root tab routes`() {
        val expected = listOf("dashboard", "positions", "orders", "transactions", "market-data", "alerts")
        expected.forEach { route ->
            assertTrue("bar attendue sur $route", showsBottomBar(route))
        }
        assertEquals(expected.toSet(), BOTTOM_BAR_ROUTES)
    }

    @Test
    fun `bottom bar is hidden on pushed screens and outside a session`() {
        listOf(
            "settings", "profile", "settings/vpn", "settings/my-devices", "settings/security",
            "devices", "device/{deviceId}", "position/{positionId}", "performance",
            "pairing/scan-vps", "login", "totp", "setup",
        ).forEach { route ->
            assertFalse("pas de barre sur $route", showsBottomBar(route))
        }
        assertFalse(showsBottomBar(null))
    }

    @Test
    fun `portfolio tab is selected on positions orders history and position detail`() {
        listOf("positions", "orders", "transactions", "position/{positionId}").forEach { route ->
            assertTrue("Portefeuille sélectionné sur $route", isBottomTabSelected(Screen.Positions, route))
        }
    }

    @Test
    fun `other tabs are not selected on portfolio routes and vice versa`() {
        assertFalse(isBottomTabSelected(Screen.Dashboard, "orders"))
        assertFalse(isBottomTabSelected(Screen.MarketData, "transactions"))
        assertFalse(isBottomTabSelected(Screen.Alerts, "positions"))
        assertFalse(isBottomTabSelected(Screen.Positions, "dashboard"))
        assertFalse(isBottomTabSelected(Screen.Positions, "market-data"))
        assertFalse(isBottomTabSelected(Screen.Positions, "settings"))
        assertFalse(isBottomTabSelected(Screen.Positions, null))
    }

    @Test
    fun `plain tabs are selected only on their own route`() {
        assertTrue(isBottomTabSelected(Screen.Dashboard, "dashboard"))
        assertTrue(isBottomTabSelected(Screen.MarketData, "market-data"))
        assertTrue(isBottomTabSelected(Screen.Alerts, "alerts"))
        assertFalse(isBottomTabSelected(Screen.Dashboard, "alerts"))
    }

    // ── Segments du portefeuille ────────────────────────────────────────────

    @Test
    fun `each portfolio segment maps to its route and back`() {
        assertEquals("positions", PortfolioSegment.Positions.route())
        assertEquals("orders", PortfolioSegment.Orders.route())
        assertEquals("transactions", PortfolioSegment.History.route())

        assertEquals(PortfolioSegment.Positions, portfolioSegmentForRoute("positions"))
        assertEquals(PortfolioSegment.Orders, portfolioSegmentForRoute("orders"))
        assertEquals(PortfolioSegment.History, portfolioSegmentForRoute("transactions"))
    }

    @Test
    fun `position detail and unrelated routes are not a portfolio segment`() {
        assertNull(portfolioSegmentForRoute("position/{positionId}"))
        assertNull(portfolioSegmentForRoute("dashboard"))
        assertNull(portfolioSegmentForRoute(null))
    }

    @Test
    fun `segment labels are the French section names`() {
        assertEquals(
            listOf("Positions", "Ordres", "Historique"),
            PortfolioSegment.entries.map { it.label },
        )
    }

    @Test
    fun `selecting a segment pops up to Positions without stacking and reuses the top entry`() {
        PortfolioSegment.entries.forEach { segment ->
            val (route, options) = navigation { navigateToPortfolioSegment(segment) }

            assertEquals(segment.route(), route)
            assertEquals("positions", options.popUpToRoute)
            assertFalse("Positions doit rester dans la pile", options.isPopUpToInclusive())
            assertTrue(options.shouldLaunchSingleTop())
            // Pas de restauration d'état : un segment est toujours rendu à neuf.
            assertFalse(options.shouldRestoreState())
        }
    }

    // ── Onglets racines ─────────────────────────────────────────────────────

    @Test
    fun `tab navigation pops to the dashboard saving state and restores the target tab state`() {
        val (route, options) = navigation { navigateToTab(Screen.Alerts.route) }

        assertEquals("alerts", route)
        assertEquals("dashboard", options.popUpToRoute)
        assertFalse(options.isPopUpToInclusive())
        assertTrue(options.shouldPopUpToSaveState())
        assertTrue(options.shouldLaunchSingleTop())
        assertTrue(options.shouldRestoreState())
    }
}
