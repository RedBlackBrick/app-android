package com.tradingplatform.app.ui.screens.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.ui.common.DataState
import com.tradingplatform.app.ui.components.ConnectionStatusIndicator
import com.tradingplatform.app.ui.components.rememberHapticFeedback
import com.tradingplatform.app.ui.screens.dashboard.components.DashboardHeroCard
import com.tradingplatform.app.ui.screens.dashboard.components.DashboardKpiRow
import com.tradingplatform.app.ui.screens.dashboard.components.DashboardRiskTile
import com.tradingplatform.app.ui.screens.dashboard.components.DashboardSkeleton
import com.tradingplatform.app.ui.theme.Spacing

/**
 * Écran d'accueil concis : héros (NAV + variation + sparkline + période), trois KPI cliquables
 * vers Performance, tuile risque (seulement en cas d'alerte) et les 3 dernières activités.
 *
 * @param onNavigateToPerformance ouvre l'écran Performance (tuiles KPI).
 * @param onNavigateToAlerts ouvre les alertes (lien « Tout voir » du flux d'activité).
 * @param onOpenSettings ouvre les réglages (action de la barre supérieure).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToPerformance: () -> Unit,
    onNavigateToAlerts: () -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val wsState by viewModel.wsConnectionState.collectAsStateWithLifecycle()
    val activityItems by viewModel.activityItems.collectAsStateWithLifecycle()
    val isWsLive by viewModel.isWsLive.collectAsStateWithLifecycle()
    val haptic = rememberHapticFeedback()

    // Skeletons uniquement au tout premier chargement (ni valeur ni erreur sur NAV et PnL).
    // Un refresh (WS, pull-to-refresh) conserve les valeurs affichées — audit #21.
    val isInitialLoading = uiState.navSummary.isInitialLoading &&
        uiState.pnlSummary.isInitialLoading

    // isRefreshing n'alimente que l'indicateur du PullToRefreshBox.
    val isRefreshing = !isInitialLoading && (
        uiState.navSummary.isRefreshing || uiState.pnlSummary.isRefreshing
    )

    // Snackbar : échec d'un refresh alors qu'une valeur (périmée) reste affichée.
    // Sans valeur, l'erreur est rendue inline dans la carte héros (pas de snackbar).
    val snackbarHostState = remember { SnackbarHostState() }
    val navError = uiState.navSummary.staleError()
    val pnlError = uiState.pnlSummary.staleError()
    val errorMessage = navError ?: pnlError

    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            val result = snackbarHostState.showSnackbar(
                message = errorMessage,
                actionLabel = "Réessayer",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.refresh()
            }
        }
    }

    // Dérivations de présentation (fonctions pures — cf. DashboardPresentation.kt).
    val kpis = remember(uiState.navSummary.value, uiState.pnlSummary.value) {
        dashboardKpis(uiState.navSummary.value, uiState.pnlSummary.value)
    }
    val riskModel = remember(uiState.circuitBreakerStatus) {
        riskTileModel(uiState.circuitBreakerStatus)
    }

    // Callback mémoïsé : haptic et viewModel sont des références stables, donc `remember {}` sans
    // clé est sûr et évite de recomposer le sélecteur à chaque mise à jour du flux WS.
    val onPeriodSelect = remember<(PnlPeriod) -> Unit> {
        { period ->
            haptic.click()
            viewModel.selectPeriod(period)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Accueil") },
                actions = {
                    ConnectionStatusIndicator(
                        state = wsState,
                        modifier = Modifier.padding(end = Spacing.xs),
                    )
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Réglages",
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        modifier = modifier,
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                if (isInitialLoading) {
                    DashboardSkeleton()
                } else {
                    // ── Héros : NAV, variation de la période, sparkline, sélecteur ──
                    DashboardHeroCard(
                        navState = uiState.navSummary,
                        pnlState = uiState.pnlSummary,
                        selectedPeriod = uiState.selectedPeriod,
                        onSelectPeriod = onPeriodSelect,
                        onRetry = { viewModel.refresh() },
                    )

                    // ── KPI de la période → écran Performance ──────────────────────
                    DashboardKpiRow(
                        kpis = kpis,
                        onClick = onNavigateToPerformance,
                    )

                    // ── Risque : uniquement circuit ouvert / statut indisponible ──
                    if (riskModel != null) {
                        DashboardRiskTile(model = riskModel)
                    }

                    // ── Activité : 3 derniers événements + « Tout voir » ───────────
                    ActivityFeedCard(
                        items = activityItems,
                        isLive = isWsLive,
                        onSeeAll = onNavigateToAlerts,
                    )
                }
            }
        }
    }
}

/** Erreur du dernier refresh quand une valeur (désormais périmée) reste affichée. */
private fun DataState<*>.staleError(): String? = if (value != null) error else null
