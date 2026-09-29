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
import com.tradingplatform.app.ui.components.PortfolioSwitcher
import com.tradingplatform.app.ui.components.rememberHapticFeedback
import com.tradingplatform.app.ui.screens.dashboard.components.DashboardHeroCard
import com.tradingplatform.app.ui.screens.dashboard.components.DashboardKpiRow
import com.tradingplatform.app.ui.screens.dashboard.components.DashboardPortfoliosCard
import com.tradingplatform.app.ui.screens.dashboard.components.DashboardRiskBanner
import com.tradingplatform.app.ui.screens.dashboard.components.DashboardSkeleton
import com.tradingplatform.app.ui.screens.dashboard.components.DashboardStrategiesRow
import com.tradingplatform.app.ui.theme.Spacing

/** Nombre maximal de lignes de squelette de la carte « Mes portefeuilles » pendant son chargement. */
private const val OVERVIEW_PLACEHOLDER_MAX_ROWS = 3

/**
 * Écran d'accueil concis, piloté par le portefeuille ACTIF : bandeau de risque (seulement en cas
 * d'alerte), héros (NAV + variation + courbe de NAV + période + pastille broker), tuiles KPI
 * cliquables vers Performance, carte « Mes portefeuilles » (seulement si le compte en a ≥ 2),
 * entrée Stratégies et les 3 dernières activités. Tous les blocs optionnels sont ABSENTS quand leur
 * donnée l'est.
 *
 * @param onNavigateToPerformance ouvre l'écran Performance (tuiles KPI).
 * @param onNavigateToAlerts ouvre les alertes (lien « Tout voir » du flux d'activité).
 * @param onOpenSettings ouvre les réglages (action de la barre supérieure).
 * @param onOpenRisk ouvre l'écran Risque (bandeau de risque).
 * @param onOpenStrategies ouvre l'écran Stratégies (ligne « Stratégies — N actives »).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToPerformance: () -> Unit,
    onNavigateToAlerts: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenRisk: () -> Unit = {},
    onOpenStrategies: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val portfolios by viewModel.portfolios.collectAsStateWithLifecycle()
    val wsState by viewModel.wsConnectionState.collectAsStateWithLifecycle()
    val activityItems by viewModel.activityItems.collectAsStateWithLifecycle()
    val isWsLive by viewModel.isWsLive.collectAsStateWithLifecycle()
    val haptic = rememberHapticFeedback()

    // Skeletons uniquement au tout premier chargement (ni valeur ni erreur sur NAV et PnL).
    // Un refresh (WS, pull-to-refresh) conserve les valeurs affichées — audit #21.
    // Un changement de portefeuille actif remet NAV et PnL à zéro → skeleton, jamais l'ancien.
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
    val currencySymbol = remember(portfolios, uiState.portfolioId) {
        activeCurrencySymbol(portfolios, uiState.portfolioId)
    }
    val kpis = remember(uiState.navSummary.value, uiState.performance, currencySymbol) {
        dashboardKpis(uiState.navSummary.value, uiState.performance, currencySymbol)
    }
    val riskBanner = remember(uiState.riskStatus, uiState.circuitBreakerStatus) {
        riskBannerModel(uiState.riskStatus, uiState.circuitBreakerStatus)
    }
    val brokerPill = remember(uiState.brokerStatus) { brokerPillModel(uiState.brokerStatus) }
    val strategiesEntry = remember(uiState.activeStrategyCount) {
        strategiesEntryModel(uiState.activeStrategyCount)
    }
    val overview = uiState.portfolioOverview
    val overviewModel = remember(overview.value, uiState.portfolioId) {
        overview.value?.let { portfolioOverviewModel(it, uiState.portfolioId) }
    }
    val showOverview = shouldShowPortfolioOverview(
        portfolioCount = portfolios.size,
        hasValue = !overview.value.isNullOrEmpty(),
        isLoading = overview.isInitialLoading,
    )

    // Callbacks mémoïsés : haptic et viewModel sont des références stables, donc `remember {}` sans
    // clé est sûr et évite de recomposer le sélecteur à chaque mise à jour du flux WS.
    val onPeriodSelect = remember<(PnlPeriod) -> Unit> {
        { period ->
            haptic.click()
            viewModel.selectPeriod(period)
        }
    }
    val onPortfolioSelect = remember<(String) -> Unit> {
        { portfolioId ->
            haptic.click()
            viewModel.selectPortfolio(portfolioId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Accueil") },
                actions = {
                    // Rend RIEN pour un compte à un seul portefeuille.
                    PortfolioSwitcher()
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
                    // ── Risque : l'UNIQUE alerte (la plus grave), absente si rien à signaler ──
                    if (riskBanner != null) {
                        DashboardRiskBanner(model = riskBanner, onClick = onOpenRisk)
                    }

                    // ── Héros : NAV, variation de la période, courbe, sélecteur, broker ──
                    DashboardHeroCard(
                        navState = uiState.navSummary,
                        pnlState = uiState.pnlSummary,
                        navCurve = uiState.navCurve,
                        selectedPeriod = uiState.selectedPeriod,
                        onSelectPeriod = onPeriodSelect,
                        onRetry = { viewModel.refresh() },
                        brokerPill = brokerPill,
                        currencySymbol = currencySymbol,
                    )

                    // ── KPI (liquidités, latent, win rate, drawdown) → écran Performance ──
                    DashboardKpiRow(
                        kpis = kpis,
                        onClick = onNavigateToPerformance,
                    )

                    // ── Mes portefeuilles : uniquement pour un compte à ≥ 2 portefeuilles ──
                    if (showOverview) {
                        DashboardPortfoliosCard(
                            model = overviewModel,
                            caption = dashboardPeriodCaption(uiState.selectedPeriod),
                            onSelect = onPortfolioSelect,
                            placeholderRows = minOf(portfolios.size, OVERVIEW_PLACEHOLDER_MAX_ROWS),
                            staleSyncedAt = if (overview.error != null && overview.value != null) {
                                overview.syncedAt
                            } else {
                                null
                            },
                        )
                    }

                    // ── Stratégies : « N actives » → écran Stratégies ─────────────────
                    if (strategiesEntry != null) {
                        DashboardStrategiesRow(model = strategiesEntry, onClick = onOpenStrategies)
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
