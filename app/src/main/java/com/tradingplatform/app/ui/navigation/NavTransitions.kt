package com.tradingplatform.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry
import com.tradingplatform.app.ui.theme.Motion

/**
 * Transitions contextuelles pour la navigation.
 *
 * - **Tabs** (Dashboard, Positions, Alerts, Devices, Settings) : crossfade rapide
 * - **Hiérarchie** (List → Detail) : slide horizontal in/out
 * - **Flux séquentiel** (pairing) : slide horizontal forward/backward
 * - **Auth** (Login, Totp) : fade
 */
object NavTransitions {

    // Routes considered as top-level tabs (crossfade only)
    private val TAB_ROUTES = setOf(
        Screen.Dashboard.route,
        Screen.MarketData.route,
        Screen.Positions.route,
        Screen.Alerts.route,
        Screen.Devices.route,
        Screen.VpnSettings.route,
        Screen.SecuritySettings.route,
        Screen.Settings.route,
    )

    // Routes that are part of a hierarchical drill-down (slide)
    private val DETAIL_ROUTES = setOf(
        Screen.PositionDetail.route,
        Screen.DeviceDetail.route,
    )

    // Routes in the pairing sequential flow
    private val PAIRING_ROUTES = setOf(
        Screen.ScanVpsQr.route,
        Screen.ScanDeviceQr.route,
        Screen.PairingProgress.route,
        Screen.PairingDone.route,
    )

    private fun isTabRoute(route: String?): Boolean = route in TAB_ROUTES
    private fun isDetailRoute(route: String?): Boolean =
        route != null && DETAIL_ROUTES.any { route.startsWith(it.substringBefore("{")) }

    private fun isPairingRoute(route: String?): Boolean = route in PAIRING_ROUTES

    // ── Enter transitions ───────────────────────────────────────────────────

    fun enterTransition(scope: AnimatedContentTransitionScope<NavBackStackEntry>): EnterTransition {
        val targetRoute = scope.targetState.destination.route
        val initialRoute = scope.initialState.destination.route

        return when {
            // Tab → Tab : crossfade
            isTabRoute(targetRoute) && isTabRoute(initialRoute) -> {
                fadeIn(animationSpec = tween(Motion.MediumDuration, easing = Motion.StandardEasing))
            }
            // Navigating into a detail screen : slide in from right
            isDetailRoute(targetRoute) -> {
                slideInHorizontally(
                    initialOffsetX = { fullWidth -> fullWidth },
                    animationSpec = tween(Motion.EnterDuration, easing = Motion.EmphasizedDecelerate),
                ) + fadeIn(animationSpec = tween(Motion.EnterDuration))
            }
            // Pairing forward flow : slide in from right
            isPairingRoute(targetRoute) -> {
                slideInHorizontally(
                    initialOffsetX = { fullWidth -> fullWidth },
                    animationSpec = tween(Motion.EnterDuration, easing = Motion.EmphasizedDecelerate),
                ) + fadeIn(animationSpec = tween(Motion.EnterDuration))
            }
            // Default : fade
            else -> fadeIn(animationSpec = tween(Motion.EnterDuration))
        }
    }

    fun exitTransition(scope: AnimatedContentTransitionScope<NavBackStackEntry>): ExitTransition {
        val targetRoute = scope.targetState.destination.route
        val initialRoute = scope.initialState.destination.route

        return when {
            // Tab → Tab : crossfade
            isTabRoute(targetRoute) && isTabRoute(initialRoute) -> {
                fadeOut(animationSpec = tween(Motion.MediumDuration, easing = Motion.StandardEasing))
            }
            // Current screen exits because a detail opened : slide out to left
            isDetailRoute(targetRoute) -> {
                slideOutHorizontally(
                    targetOffsetX = { fullWidth -> -fullWidth / 4 },
                    animationSpec = tween(Motion.ExitDuration, easing = Motion.EmphasizedAccelerate),
                ) + fadeOut(animationSpec = tween(Motion.ExitDuration))
            }
            // Pairing forward flow : slide out to left
            isPairingRoute(targetRoute) && isPairingRoute(initialRoute) -> {
                slideOutHorizontally(
                    targetOffsetX = { fullWidth -> -fullWidth / 4 },
                    animationSpec = tween(Motion.ExitDuration, easing = Motion.EmphasizedAccelerate),
                ) + fadeOut(animationSpec = tween(Motion.ExitDuration))
            }
            // Default : fade
            else -> fadeOut(animationSpec = tween(Motion.ExitDuration))
        }
    }

    // ── Pop transitions (back navigation) ───────────────────────────────────

    fun popEnterTransition(scope: AnimatedContentTransitionScope<NavBackStackEntry>): EnterTransition {
        val targetRoute = scope.targetState.destination.route
        val initialRoute = scope.initialState.destination.route

        return when {
            // Returning from detail to list : slide in from left
            isDetailRoute(initialRoute) -> {
                slideInHorizontally(
                    initialOffsetX = { fullWidth -> -fullWidth / 4 },
                    animationSpec = tween(Motion.EnterDuration, easing = Motion.EmphasizedDecelerate),
                ) + fadeIn(animationSpec = tween(Motion.EnterDuration))
            }
            // Pairing back : slide in from left
            isPairingRoute(initialRoute) && isPairingRoute(targetRoute) -> {
                slideInHorizontally(
                    initialOffsetX = { fullWidth -> -fullWidth / 4 },
                    animationSpec = tween(Motion.EnterDuration, easing = Motion.EmphasizedDecelerate),
                ) + fadeIn(animationSpec = tween(Motion.EnterDuration))
            }
            // Default : fade
            else -> fadeIn(animationSpec = tween(Motion.EnterDuration))
        }
    }

    fun popExitTransition(scope: AnimatedContentTransitionScope<NavBackStackEntry>): ExitTransition {
        val initialRoute = scope.initialState.destination.route

        return when {
            // Pop out of detail : slide out to right
            isDetailRoute(initialRoute) -> {
                slideOutHorizontally(
                    targetOffsetX = { fullWidth -> fullWidth },
                    animationSpec = tween(Motion.ExitDuration, easing = Motion.EmphasizedAccelerate),
                ) + fadeOut(animationSpec = tween(Motion.ExitDuration))
            }
            // Pairing back : slide out to right
            isPairingRoute(initialRoute) -> {
                slideOutHorizontally(
                    targetOffsetX = { fullWidth -> fullWidth },
                    animationSpec = tween(Motion.ExitDuration, easing = Motion.EmphasizedAccelerate),
                ) + fadeOut(animationSpec = tween(Motion.ExitDuration))
            }
            // Default : fade
            else -> fadeOut(animationSpec = tween(Motion.ExitDuration))
        }
    }
}
