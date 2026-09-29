package com.tradingplatform.app.ui.navigation

import androidx.navigation.NavController
import com.tradingplatform.app.ui.components.PortfolioSegment

/**
 * Routes des onglets racines : celles où la [BottomNavBar] est affichée.
 *
 * L'onglet « Portefeuille » couvre trois routes racines (positions / ordres / historique,
 * reliées par [com.tradingplatform.app.ui.components.PortfolioSegmentedTabs]). Le détail d'une
 * position, les Réglages (et leurs sous-écrans), la flotte Devices, Performance, le pairing, etc.
 * sont des écrans poussés : pas de barre du bas.
 */
val BOTTOM_BAR_ROUTES: Set<String> = setOf(
    Screen.Dashboard.route,
    Screen.Positions.route,
    Screen.Orders.route,
    Screen.TransactionHistory.route,
    Screen.MarketData.route,
    Screen.Alerts.route,
)

/**
 * Routes rattachées à l'onglet « Portefeuille » (celui-ci reste sélectionné dans la barre).
 * `PositionDetail.route` est le gabarit `position/{positionId}` — c'est aussi ce que renvoie
 * `NavDestination.route` pour une entrée ouverte avec un identifiant.
 */
val PORTFOLIO_ROUTES: Set<String> = setOf(
    Screen.Positions.route,
    Screen.Orders.route,
    Screen.TransactionHistory.route,
    Screen.PositionDetail.route,
)

fun isPortfolioRoute(route: String?): Boolean = route in PORTFOLIO_ROUTES

/** Affiche-t-on la barre du bas sur [route] ? (`null` = pas de destination courante.) */
fun showsBottomBar(route: String?): Boolean = route in BOTTOM_BAR_ROUTES

/**
 * Un onglet de la barre est-il sélectionné pour la destination courante [currentRoute] ?
 * Portefeuille (route racine `Screen.Positions`) l'est pour toutes les routes du portefeuille.
 */
fun isBottomTabSelected(tab: Screen, currentRoute: String?): Boolean =
    if (tab == Screen.Positions) isPortfolioRoute(currentRoute) else currentRoute == tab.route

/** Route de navigation d'un segment du portefeuille. */
fun PortfolioSegment.route(): String = when (this) {
    PortfolioSegment.Positions -> Screen.Positions.route
    PortfolioSegment.Orders -> Screen.Orders.route
    PortfolioSegment.History -> Screen.TransactionHistory.route
}

/**
 * Segment à mettre en avant pour [route], ou `null` si la route n'est pas une section du
 * portefeuille (le détail d'une position n'affiche pas la rangée de segments).
 */
fun portfolioSegmentForRoute(route: String?): PortfolioSegment? =
    PortfolioSegment.entries.firstOrNull { it.route() == route }

/**
 * Navigue vers un onglet racine de la barre du bas (Accueil, Portefeuille, Marchés, Alertes).
 *
 * Remplace la back stack jusqu'à l'Accueil en sauvegardant / restaurant l'état de chaque onglet
 * (`saveState` / `restoreState`) et évite les doublons (`launchSingleTop`). Partagé par la barre
 * et par les liens « voir les alertes » de l'Accueil.
 */
fun NavController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(Screen.Dashboard.route) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Bascule entre les segments Positions / Ordres / Historique **sans empiler** la back stack.
 *
 * `popUpTo(Positions)` (non inclusif) dépile la section précédente ; `launchSingleTop` évite un
 * doublon quand la cible est déjà au sommet. Résultat : la pile reste `[Accueil, Positions]` ou
 * `[Accueil, Positions, <segment courant>]`, jamais plus profonde.
 *
 * Bouton retour (décision) : depuis Ordres / Historique, retour → **Positions** (racine de
 * l'onglet Portefeuille), puis retour depuis Positions → Accueil. Positions reste donc en
 * dessous de toute section, ce qui garde l'état sauvegardé de l'onglet cohérent (`saveState`
 * sur la racine Positions) et permet à un re-tap sur l'onglet Portefeuille de revenir à
 * Positions.
 */
fun NavController.navigateToPortfolioSegment(segment: PortfolioSegment) {
    navigate(segment.route()) {
        popUpTo(Screen.Positions.route) { inclusive = false }
        launchSingleTop = true
    }
}
