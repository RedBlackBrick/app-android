package com.tradingplatform.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import com.tradingplatform.app.ui.components.PortfolioSegment

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
 * Bottom navigation bar — 4 onglets fixes.
 *
 * Tabs (in order):
 * 1. Accueil      — [Screen.Dashboard]
 * 2. Portefeuille — [Screen.Positions] (racine ; reste sélectionné sur Ordres, Historique et
 *    le détail d'une position, voir [isBottomTabSelected])
 * 3. Marchés      — [Screen.MarketData]
 * 4. Alertes      — [Screen.Alerts], avec badge de non-lus ([unreadAlertCount])
 *
 * Plus d'onglet Devices ni Paramètres : les Réglages s'ouvrent depuis l'icône de la TopAppBar
 * des écrans racines, et la flotte Devices (admin) depuis les Réglages.
 *
 * Navigation via [navigateToTab] (popUpTo Accueil + launchSingleTop + saveState/restoreState).
 * Re-taper sur Portefeuille alors qu'il est déjà sélectionné revient à sa racine (Positions).
 */
@Composable
fun BottomNavBar(
    navController: NavController,
    modifier: Modifier = Modifier,
    unreadAlertCount: Int = 0,
) {
    val items = listOf(
        BottomNavItem(
            screen = Screen.Dashboard,
            label = "Accueil",
            icon = Icons.Filled.Home,
        ),
        BottomNavItem(
            screen = Screen.Positions,
            label = "Portefeuille",
            icon = Icons.AutoMirrored.Filled.List,
        ),
        BottomNavItem(
            screen = Screen.MarketData,
            label = "March\u00e9s",
            icon = Icons.Filled.ShowChart,
        ),
        BottomNavItem(
            screen = Screen.Alerts,
            label = "Alertes",
            icon = Icons.Filled.Notifications,
        ),
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    NavigationBar(modifier = modifier) {
        items.forEach { item ->
            val selected = isBottomTabSelected(item.screen, currentRoute)

            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (selected && item.screen == Screen.Positions) {
                        // Re-tap sur Portefeuille : retour à la racine de l'onglet (Positions)
                        // plutôt qu'une restauration de l'état déjà affiché.
                        navController.navigateToPortfolioSegment(PortfolioSegment.Positions)
                    } else {
                        navController.navigateToTab(item.screen.route)
                    }
                },
                icon = {
                    if (item.screen == Screen.Alerts && unreadAlertCount > 0) {
                        BadgedBox(
                            badge = {
                                Badge {
                                    Text(
                                        text = if (unreadAlertCount > 99) "99+" else "$unreadAlertCount",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            },
                        ) {
                            Icon(
                                imageVector = if (selected) item.selectedIcon else item.icon,
                                contentDescription = null,
                                tint = if (selected)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Icon(
                            imageVector = if (selected) item.selectedIcon else item.icon,
                            contentDescription = null,
                            tint = if (selected)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                label = {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                modifier = Modifier.semantics {
                    contentDescription = if (item.screen == Screen.Alerts && unreadAlertCount > 0) {
                        "${item.label} — $unreadAlertCount non lues"
                    } else {
                        item.label
                    }
                },
            )
        }
    }
}
