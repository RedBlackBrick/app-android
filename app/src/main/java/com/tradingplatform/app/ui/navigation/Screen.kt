package com.tradingplatform.app.ui.navigation

import android.net.Uri

/**
 * Sealed class representing all navigation destinations in the app.
 *
 * Each destination has a [route] string used by the NavHost. Destinations with
 * path arguments provide a [createRoute] factory function.
 */
sealed class Screen(val route: String) {

    /** Shown on first launch — guides the user through the WireGuard onboarding QR scan. */
    data object Setup : Screen("setup")

    data object Login : Screen("login")

    /** Token is stored via SessionManager — never in the route (security). */
    data object Totp : Screen("totp")

    data object Dashboard : Screen("dashboard")

    data object MarketData : Screen("market-data")

    /** Racine de l'onglet Portefeuille (segment « Positions »). */
    data object Positions : Screen("positions")

    /** Detailed portfolio performance metrics (Sharpe, Sortino, drawdown, etc.). */
    data object Performance : Screen("performance")

    /** Global transaction history across all positions (segment « Historique » du Portefeuille). */
    data object TransactionHistory : Screen("transactions")

    /** Read-only orders list (active + history) — segment « Ordres » du Portefeuille. */
    data object Orders : Screen("orders")

    data object PositionDetail : Screen("position/{positionId}") {
        fun createRoute(positionId: Int): String = "position/$positionId"
    }

    /**
     * Admin only — flotte d'appareils. Plus d'onglet : entrée « Flotte d'appareils (admin) » des
     * Réglages, visible uniquement si is_admin ; la route reste gardée (redirection Dashboard).
     */
    data object Devices : Screen("devices")

    data object DeviceDetail : Screen("device/{deviceId}") {
        fun createRoute(deviceId: String): String = "device/${Uri.encode(deviceId)}"
    }

    data object ScanVpsQr : Screen("pairing/scan-vps")

    data object ScanDeviceQr : Screen("pairing/scan-device")

    data object PairingProgress : Screen("pairing/progress")

    data object PairingDone : Screen("pairing/done")

    data object Alerts : Screen("alerts")

    /** User profile screen. */
    data object Profile : Screen("profile")

    /** Centralized settings hub — écran poussé depuis l'icône Réglages des écrans racines (plus un onglet). */
    data object Settings : Screen("settings")

    data object VpnSettings : Screen("settings/vpn")

    /** User's own devices — accessible to all authenticated users. */
    data object MyDevices : Screen("settings/my-devices")

    data object SecuritySettings : Screen("settings/security")

    /** Liens portefeuille-stratégie du portefeuille actif (pause / réactivation) — écran poussé depuis l'Accueil. */
    data object Strategies : Screen("strategies")

    /** État du risque du portefeuille actif + kill switch (activation seule) — écran poussé depuis l'Accueil. */
    data object Risk : Screen("risk")

    /** Préférences push par catégorie (lecture/écriture de `ui.notifications`) — entrée des Réglages. */
    data object NotificationPrefs : Screen("settings/notifications")
}
