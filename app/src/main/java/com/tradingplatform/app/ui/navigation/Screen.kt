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

    data object Positions : Screen("positions")

    /** Detailed portfolio performance metrics (Sharpe, Sortino, drawdown, etc.). */
    data object Performance : Screen("performance")

    /** Global transaction history across all positions. */
    data object TransactionHistory : Screen("transactions")

    data object PositionDetail : Screen("position/{positionId}") {
        fun createRoute(positionId: Int): String = "position/$positionId"
    }

    /** Admin only — conditionally displayed based on is_admin flag. */
    data object Devices : Screen("devices")

    data object DeviceDetail : Screen("device/{deviceId}") {
        fun createRoute(deviceId: String): String = "device/${Uri.encode(deviceId)}"
    }

    data object LocalMaintenance : Screen("local-maintenance/{deviceId}") {
        fun createRoute(deviceId: String): String = "local-maintenance/${Uri.encode(deviceId)}"
    }

    data object ScanVpsQr : Screen("pairing/scan-vps")

    data object ScanDeviceQr : Screen("pairing/scan-device")

    data object PairingProgress : Screen("pairing/progress")

    data object PairingDone : Screen("pairing/done")

    data object Alerts : Screen("alerts")

    /** User profile screen. */
    data object Profile : Screen("profile")

    /** Centralized settings hub. */
    data object Settings : Screen("settings")

    data object VpnSettings : Screen("settings/vpn")

    /** User's own devices — accessible to all authenticated users. */
    data object MyDevices : Screen("settings/my-devices")

    data object SecuritySettings : Screen("settings/security")
}
