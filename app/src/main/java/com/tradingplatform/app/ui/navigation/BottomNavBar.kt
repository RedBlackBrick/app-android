package com.tradingplatform.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState

/**
 * Represents a single item in the bottom navigation bar.
 *
 * @param screen      The navigation destination.
 * @param label       Display label shown below the icon.
 * @param icon        Default (unselected) icon.
 * @param selectedIcon Icon shown when this destination is currently selected.
 */
data class BottomNavItem(
    val screen: Screen,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon,
)

/**
 * Bottom navigation bar with 4 standard tabs and 1 conditional admin tab.
 *
 * Tabs (in order):
 * 1. Dashboard
 * 2. Positions
 * 3. Alertes
 * 4. Devices  — visible only when [isAdmin] is true
 * 5. Paramètres
 *
 * Navigation is performed by replacing the back stack up to the selected root
 * destination (launchSingleTop + restoreState) to avoid duplicate entries.
 */
@Composable
fun BottomNavBar(
    navController: NavController,
    isAdmin: Boolean,
    modifier: Modifier = Modifier,
) {
    val baseItems = listOf(
        BottomNavItem(
            screen = Screen.Dashboard,
            label = "Dashboard",
            icon = Icons.Filled.Home,
        ),
        BottomNavItem(
            screen = Screen.Positions,
            label = "Positions",
            icon = Icons.AutoMirrored.Filled.List,
        ),
        BottomNavItem(
            screen = Screen.Alerts,
            label = "Alertes",
            icon = Icons.Filled.Notifications,
        ),
    )

    val adminItem = BottomNavItem(
        screen = Screen.Devices,
        label = "Devices",
        icon = Icons.Filled.Star,
    )

    val settingsItem = BottomNavItem(
        screen = Screen.Settings,
        label = "Paramètres",
        icon = Icons.Filled.Settings,
    )

    // Build the ordered list: base + optional Devices + Settings
    val items = buildList {
        addAll(baseItems)
        if (isAdmin) add(adminItem)
        add(settingsItem)
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // The settings section covers the hub and all settings sub-screens
    val settingsRoutes = setOf(
        Screen.Settings.route,
        Screen.VpnSettings.route,
        Screen.SecuritySettings.route,
    )

    NavigationBar(modifier = modifier) {
        items.forEach { item ->
            val selected = when {
                item.screen.route in settingsRoutes ->
                    currentRoute in settingsRoutes
                else -> currentRoute == item.screen.route
            }

            NavigationBarItem(
                selected = selected,
                onClick = {
                    navController.navigate(item.screen.route) {
                        // Pop up to the dashboard to avoid a growing back stack
                        popUpTo(Screen.Dashboard.route) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = {
                    Icon(
                        imageVector = if (selected) item.selectedIcon else item.icon,
                        contentDescription = null,
                        tint = if (selected)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                label = {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                modifier = Modifier.semantics {
                    contentDescription = item.label
                },
            )
        }
    }
}
