package com.tradingplatform.app.ui.navigation

import android.app.Activity
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tradingplatform.app.domain.usecase.auth.GetAuthContextUseCase
import com.tradingplatform.app.vpn.VpnState
import com.tradingplatform.app.vpn.WireGuardManager
import com.tradingplatform.app.ui.components.VpnStatusBanner
import com.tradingplatform.app.ui.screens.alerts.AlertListScreen
import com.tradingplatform.app.ui.screens.auth.LoginScreen
import com.tradingplatform.app.ui.screens.dashboard.DashboardScreen
import com.tradingplatform.app.ui.screens.devices.DeviceListScreen
import com.tradingplatform.app.ui.screens.devices.EdgeDeviceDashboardScreen
import com.tradingplatform.app.ui.screens.maintenance.LocalMaintenanceScreen
import com.tradingplatform.app.ui.screens.pairing.PairingDoneScreen
import com.tradingplatform.app.ui.screens.pairing.PairingProgressScreen
import com.tradingplatform.app.ui.screens.pairing.PairingViewModel
import com.tradingplatform.app.ui.screens.pairing.ScanDeviceQrScreen
import com.tradingplatform.app.ui.screens.pairing.ScanVpsQrScreen
import com.tradingplatform.app.ui.screens.portfolio.PositionDetailScreen
import com.tradingplatform.app.ui.screens.portfolio.PositionsScreen
import com.tradingplatform.app.ui.screens.settings.MyDevicesScreen
import com.tradingplatform.app.ui.screens.settings.SecuritySettingsScreen
import com.tradingplatform.app.ui.screens.settings.SettingsScreen
import com.tradingplatform.app.ui.screens.settings.VpnSettingsScreen
import com.tradingplatform.app.ui.screens.setup.SetupScreen
import com.tradingplatform.app.ui.screens.totp.TotpScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

// ── AppNavViewModel ────────────────────────────────────────────────────────────

/**
 * Minimal ViewModel that reads the persisted auth state and admin flag from
 * [EncryptedDataStore] once at startup to determine the initial navigation
 * destination and to drive the [BottomNavBar] admin tab visibility.
 *
 * [isLoggedIn] is a tri-state:
 * - `null`  — checking (datastore read in flight)
 * - `true`  — access token present → start at Dashboard
 * - `false` — no token → start at Login
 */
@HiltViewModel
class AppNavViewModel @Inject constructor(
    private val getAuthContextUseCase: GetAuthContextUseCase,
    wireGuardManager: WireGuardManager,
) : ViewModel() {

    /** VPN connection state — exposed for VpnStatusBanner. */
    val vpnState: StateFlow<VpnState> = wireGuardManager.state

    private val _isLoggedIn = MutableStateFlow<Boolean?>(null)
    val isLoggedIn: StateFlow<Boolean?> = _isLoggedIn.asStateFlow()

    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    /**
     * Tri-state:
     * - `null`  — datastore read in flight
     * - `true`  — setup QR already scanned and VPN connected once → skip SetupScreen
     * - `false` — first launch or setup not completed → show SetupScreen
     */
    private val _isSetupCompleted = MutableStateFlow<Boolean?>(null)
    val isSetupCompleted: StateFlow<Boolean?> = _isSetupCompleted.asStateFlow()

    init {
        viewModelScope.launch {
            val context = getAuthContextUseCase()
            _isAdmin.value = context.isAdmin
            _isLoggedIn.value = context.isLoggedIn
            _isSetupCompleted.value = context.setupCompleted
            Timber.d(
                "AppNavViewModel: isLoggedIn=${context.isLoggedIn}, " +
                    "isAdmin=${context.isAdmin}, setupCompleted=${context.setupCompleted}"
            )
        }
    }
}

// ── AppNavGraph ────────────────────────────────────────────────────────────────

private const val PAIRING_GRAPH_ROUTE = "pairing_graph/{source}"
private const val PAIRING_SOURCE_DEVICES = "devices"
private const val PAIRING_SOURCE_MY_DEVICES = "my-devices"

private fun pairingGraphRoute(source: String) = "pairing_graph/$source"

private fun pairingReturnRoute(source: String?) = when (source) {
    PAIRING_SOURCE_MY_DEVICES -> Screen.MyDevices.route
    else -> Screen.Devices.route
}

/**
 * Root navigation graph for the application.
 *
 * Reads the auth state from [AppNavViewModel] to pick the correct start destination.
 * While the state is being determined (null), nothing is rendered to avoid a flash.
 *
 * FCM deep link: reads the "navigate_to" extra from the current Activity intent
 * and navigates to [Screen.Alerts] if set to "alerts".
 *
 * Pairing flow: the four pairing screens share a single [PairingViewModel] instance
 * scoped to the nested "pairing_graph" navigation graph via
 * `hiltViewModel(navController.getBackStackEntry(PAIRING_GRAPH_ROUTE))`.
 *
 * Admin guard: navigating to [Screen.Devices] when [AppNavViewModel.isAdmin] is false
 * redirects to [Screen.Dashboard].
 */
@Composable
fun AppNavGraph(
    modifier: Modifier = Modifier,
    appNavViewModel: AppNavViewModel = hiltViewModel(),
) {
    val isLoggedIn by appNavViewModel.isLoggedIn.collectAsStateWithLifecycle()
    val isAdmin by appNavViewModel.isAdmin.collectAsStateWithLifecycle()
    val isSetupCompleted by appNavViewModel.isSetupCompleted.collectAsStateWithLifecycle()
    val vpnState by appNavViewModel.vpnState.collectAsStateWithLifecycle()

    // Wait until all datastore checks complete before rendering anything.
    // isLoggedIn and isSetupCompleted are set atomically in the same init coroutine,
    // so checking isSetupCompleted alone is sufficient — but both are null initially.
    val loggedIn = isLoggedIn ?: return
    val setupCompleted = isSetupCompleted ?: return

    val startDestination = when {
        !setupCompleted -> Screen.Setup.route
        loggedIn -> Screen.Dashboard.route
        else -> Screen.Login.route
    }

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Routes where the BottomNavBar is shown
    val bottomBarRoutes = setOf(
        Screen.Dashboard.route,
        Screen.Positions.route,
        Screen.Alerts.route,
        Screen.Devices.route,
        Screen.Settings.route,
        Screen.VpnSettings.route,
        Screen.MyDevices.route,
        Screen.SecuritySettings.route,
    )
    val showBottomBar = currentRoute in bottomBarRoutes

    // FCM deep link — read "navigate_to" extra from the host Activity
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val activity = context as? Activity
        val navigateTo = activity?.intent?.getStringExtra("navigate_to")
        if (navigateTo == "alerts" && loggedIn) {
            navController.navigate(Screen.Alerts.route) {
                popUpTo(Screen.Dashboard.route) { saveState = false }
                launchSingleTop = true
            }
        }
    }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (showBottomBar) {
                BottomNavBar(
                    navController = navController,
                    isAdmin = isAdmin,
                )
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            // Global VPN disconnect banner
            val isVpnDisconnected = loggedIn && vpnState !is VpnState.Connected
            VpnStatusBanner(isDisconnected = isVpnDisconnected)

        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.fillMaxSize(),
            enterTransition = { NavTransitions.enterTransition(this) },
            exitTransition = { NavTransitions.exitTransition(this) },
            popEnterTransition = { NavTransitions.popEnterTransition(this) },
            popExitTransition = { NavTransitions.popExitTransition(this) },
        ) {

            // ── Onboarding setup (first launch only) ─────────────────────────

            composable(Screen.Setup.route) {
                SetupScreen(
                    onSetupComplete = {
                        navController.navigate(Screen.Login.route) {
                            popUpTo(Screen.Setup.route) { inclusive = true }
                        }
                    },
                )
            }

            // ── Auth ──────────────────────────────────────────────────────────

            composable(Screen.Login.route) {
                LoginScreen(
                    onNavigateToDashboard = {
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    },
                    onNavigateToTotp = { sessionToken ->
                        navController.navigate(Screen.Totp.createRoute(sessionToken))
                    },
                )
            }

            composable(
                route = Screen.Totp.route,
                arguments = listOf(
                    navArgument("sessionToken") { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val sessionToken = backStackEntry.arguments?.getString("sessionToken") ?: ""
                TotpScreen(
                    sessionToken = sessionToken,
                    onNavigateToDashboard = {
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                )
            }

            // ── Main tabs ─────────────────────────────────────────────────────

            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    onNavigateToPositions = {
                        navController.navigate(Screen.Positions.route)
                    },
                )
            }

            composable(Screen.Positions.route) {
                PositionsScreen(
                    onNavigateToDetail = { positionId ->
                        navController.navigate(Screen.PositionDetail.createRoute(positionId))
                    },
                )
            }

            composable(
                route = Screen.PositionDetail.route,
                arguments = listOf(
                    navArgument("positionId") { type = NavType.IntType },
                ),
            ) { backStackEntry ->
                val positionId = backStackEntry.arguments?.getInt("positionId") ?: -1
                PositionDetailScreen(
                    positionId = positionId,
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            composable(Screen.Alerts.route) {
                AlertListScreen()
            }

            // ── Devices (admin only) ──────────────────────────────────────────

            composable(Screen.Devices.route) {
                // Admin guard — redirect to Dashboard if not admin
                if (!isAdmin) {
                    LaunchedEffect(Unit) {
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(Screen.Devices.route) { inclusive = true }
                        }
                    }
                    return@composable
                }
                DeviceListScreen(
                    onNavigateToDetail = { deviceId ->
                        navController.navigate(Screen.DeviceDetail.createRoute(deviceId))
                    },
                    onNavigateToPairing = {
                        navController.navigate(pairingGraphRoute(PAIRING_SOURCE_DEVICES))
                    },
                )
            }

            composable(
                route = Screen.DeviceDetail.route,
                arguments = listOf(
                    navArgument("deviceId") { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val deviceId = backStackEntry.arguments?.getString("deviceId") ?: ""
                EdgeDeviceDashboardScreen(
                    deviceId = deviceId,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToLocalMaintenance = {
                        navController.navigate(Screen.LocalMaintenance.createRoute(deviceId))
                    },
                )
            }

            // ── Local maintenance (admin — device offline) ────────────────────

            composable(
                route = Screen.LocalMaintenance.route,
                arguments = listOf(
                    navArgument("deviceId") { type = NavType.StringType },
                ),
            ) {
                LocalMaintenanceScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            // ── Pairing nested graph ──────────────────────────────────────────
            // All four screens share a single PairingViewModel scoped to this graph.

            navigation(
                startDestination = Screen.ScanVpsQr.route,
                route = PAIRING_GRAPH_ROUTE,
                arguments = listOf(
                    navArgument("source") { type = NavType.StringType },
                ),
            ) {
                composable(Screen.ScanVpsQr.route) { backStackEntry ->
                    val pairingEntry = remember(navController) {
                        navController.getBackStackEntry(PAIRING_GRAPH_ROUTE)
                    }
                    val pairingViewModel: PairingViewModel = hiltViewModel(pairingEntry)
                    val source = pairingEntry.arguments?.getString("source")
                    val returnRoute = pairingReturnRoute(source)
                    ScanVpsQrScreen(
                        onNavigateToScanDevice = {
                            navController.navigate(Screen.ScanDeviceQr.route)
                        },
                        onNavigateToProgress = {
                            navController.navigate(Screen.PairingProgress.route) {
                                popUpTo(Screen.ScanVpsQr.route) { inclusive = false }
                            }
                        },
                        onBack = {
                            navController.popBackStack(
                                route = returnRoute,
                                inclusive = false,
                            )
                        },
                        viewModel = pairingViewModel,
                    )
                }

                composable(Screen.ScanDeviceQr.route) {
                    val pairingEntry = remember(navController) {
                        navController.getBackStackEntry(PAIRING_GRAPH_ROUTE)
                    }
                    val pairingViewModel: PairingViewModel = hiltViewModel(pairingEntry)
                    ScanDeviceQrScreen(
                        onNavigateToProgress = {
                            navController.navigate(Screen.PairingProgress.route) {
                                popUpTo(Screen.ScanVpsQr.route) { inclusive = false }
                            }
                        },
                        onBack = { navController.popBackStack() },
                        viewModel = pairingViewModel,
                    )
                }

                composable(Screen.PairingProgress.route) {
                    val pairingEntry = remember(navController) {
                        navController.getBackStackEntry(PAIRING_GRAPH_ROUTE)
                    }
                    val pairingViewModel: PairingViewModel = hiltViewModel(pairingEntry)
                    PairingProgressScreen(
                        onPairingComplete = {
                            navController.navigate(Screen.PairingDone.route) {
                                popUpTo(Screen.PairingProgress.route) { inclusive = true }
                            }
                        },
                        viewModel = pairingViewModel,
                    )
                }

                composable(Screen.PairingDone.route) {
                    val pairingEntry = remember(navController) {
                        navController.getBackStackEntry(PAIRING_GRAPH_ROUTE)
                    }
                    val pairingViewModel: PairingViewModel = hiltViewModel(pairingEntry)
                    val source = pairingEntry.arguments?.getString("source")
                    val returnRoute = pairingReturnRoute(source)
                    val step by pairingViewModel.step.collectAsStateWithLifecycle()
                    PairingDoneScreen(
                        step = step,
                        onRetry = {
                            pairingViewModel.reset()
                            navController.navigate(Screen.ScanVpsQr.route) {
                                popUpTo(Screen.ScanVpsQr.route) { inclusive = true }
                            }
                        },
                        onFinish = {
                            navController.navigate(returnRoute) {
                                popUpTo(PAIRING_GRAPH_ROUTE) { inclusive = true }
                            }
                        },
                        viewModel = pairingViewModel,
                    )
                }
            }

            // ── Settings ──────────────────────────────────────────────────────

            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateToVpn = {
                        navController.navigate(Screen.VpnSettings.route)
                    },
                    onNavigateToMyDevices = {
                        navController.navigate(Screen.MyDevices.route)
                    },
                    onNavigateToSecurity = {
                        navController.navigate(Screen.SecuritySettings.route)
                    },
                )
            }

            composable(Screen.MyDevices.route) {
                MyDevicesScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToPairing = {
                        navController.navigate(pairingGraphRoute(PAIRING_SOURCE_MY_DEVICES))
                    },
                )
            }

            composable(Screen.VpnSettings.route) {
                VpnSettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            composable(Screen.SecuritySettings.route) {
                SecuritySettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }
        }
        } // Column
    }
}
