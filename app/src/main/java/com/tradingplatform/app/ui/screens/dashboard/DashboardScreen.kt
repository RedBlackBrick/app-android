package com.tradingplatform.app.ui.screens.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.Quote
import com.tradingplatform.app.ui.components.AnimatedPnlText
import com.tradingplatform.app.ui.components.CacheTimestamp
import com.tradingplatform.app.ui.components.ConnectionStatusIndicator
import com.tradingplatform.app.ui.components.MoneyText
import com.tradingplatform.app.ui.components.SkeletonDashboardCard
import com.tradingplatform.app.ui.components.SparklineChart
import com.tradingplatform.app.ui.components.rememberHapticFeedback
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToPositions: () -> Unit,
    onNavigateToPerformance: () -> Unit,
    onNavigateToTransactions: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val wsState by viewModel.wsConnectionState.collectAsStateWithLifecycle()
    val activityItems by viewModel.activityItems.collectAsStateWithLifecycle()
    val isWsLive by viewModel.isWsLive.collectAsStateWithLifecycle()
    val haptic = rememberHapticFeedback()

    val isInitialLoading = uiState.navSummary is NavUiState.Loading &&
        uiState.pnlSummary is PnlUiState.Loading

    val isRefreshing = !isInitialLoading && (
        uiState.pnlSummary is PnlUiState.Loading || uiState.navSummary is NavUiState.Loading
    )

    // Snackbar for transient errors (replaces persistent ErrorBanner)
    val snackbarHostState = remember { SnackbarHostState() }
    val navError = (uiState.navSummary as? NavUiState.Error)?.message
    val pnlError = (uiState.pnlSummary as? PnlUiState.Error)?.message
    val quoteError = (uiState.quote as? QuoteUiState.Error)?.message
    val errorMessage = navError ?: pnlError ?: quoteError

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dashboard") },
                actions = {
                    ConnectionStatusIndicator(
                        state = wsState,
                        modifier = Modifier.padding(end = Spacing.md),
                    )
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
                    // ── Skeleton loading ────────────────────────────────────────
                    SkeletonDashboardCard()
                    SkeletonDashboardCard()
                    SkeletonDashboardCard()
                } else {
                    // ── NAV (Net Asset Value) ───────────────────────────────────
                    NavSection(navState = uiState.navSummary)

                    // ── Sparkline chart ─────────────────────────────────────────
                    val pnlData = (uiState.pnlSummary as? PnlUiState.Success)?.data
                    if (pnlData != null && pnlData.sparklinePoints.isNotEmpty()) {
                        SparklineChart(
                            dataPoints = pnlData.sparklinePoints,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // ── P&L period chips ────────────────────────────────────────
                    // Memoised callback — haptic and viewModel are stable refs that
                    // never change across recompositions, so remember {} without keys
                    // is safe and avoids recomposing PnlPeriodChips on every quote tick.
                    val onPeriodSelect = remember<(PnlPeriod) -> Unit> {
                        { period ->
                            haptic.click()
                            viewModel.selectPeriod(period)
                        }
                    }
                    PnlPeriodChips(
                        selected = uiState.selectedPeriod,
                        onSelect = onPeriodSelect,
                    )

                    // ── P&L summary ─────────────────────────────────────────────
                    PnlSection(pnlState = uiState.pnlSummary)

                    // ── Quote ────────────────────────────────────────────────────
                    QuoteSection(quoteState = uiState.quote)

                    // ── Activity feed ─────────────────────────────────────────
                    ActivityFeedCard(
                        items = activityItems,
                        isLive = isWsLive,
                    )

                    // ── Navigation ──────────────────────────────────────────────
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Button(
                        onClick = onNavigateToPositions,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Voir positions")
                    }
                    Button(
                        onClick = onNavigateToPerformance,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Voir performance")
                    }
                    Button(
                        onClick = onNavigateToTransactions,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Voir historique")
                    }
                }
            }
        }
    }
}

// ── Section composables ───────────────────────────────────────────────────────

@Composable
private fun NavSection(
    navState: NavUiState,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = extendedColors.cardSurface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = "Valeur liquidative",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (navState) {
                is NavUiState.Loading -> {
                    Text(
                        text = "—",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                is NavUiState.Success -> {
                    val nav = navState.data
                    MoneyText(
                        amount = nav.currentValue,
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "Valeur liquidative totale : ${nav.currentValue} €"
                            },
                    )
                    val positionsValue = nav.currentValue - nav.cashBalance
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(
                            modifier = Modifier.semantics {
                                contentDescription = "Cash : ${nav.cashBalance} euros"
                            },
                        ) {
                            Text(
                                text = "Cash",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            MoneyText(
                                amount = nav.cashBalance,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Column(
                            horizontalAlignment = Alignment.End,
                            modifier = Modifier.semantics {
                                contentDescription = "Positions : $positionsValue euros"
                            },
                        ) {
                            Text(
                                text = "Positions",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            MoneyText(
                                amount = positionsValue,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                is NavUiState.Error -> {
                    Text(
                        text = "Indisponible",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun PnlPeriodChips(
    selected: PnlPeriod,
    onSelect: (PnlPeriod) -> Unit,
    modifier: Modifier = Modifier,
) {
    val periods = listOf(PnlPeriod.DAY, PnlPeriod.WEEK, PnlPeriod.MONTH, PnlPeriod.YEAR)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        periods.forEach { period ->
            FilterChip(
                selected = period == selected,
                onClick = { onSelect(period) },
                label = {
                    Text(
                        text = when (period) {
                            PnlPeriod.DAY -> "Jour"
                            PnlPeriod.WEEK -> "Semaine"
                            PnlPeriod.MONTH -> "Mois"
                            PnlPeriod.YEAR -> "Année"
                            PnlPeriod.ALL -> "Tout"
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
            )
        }
    }
}

@Composable
private fun PnlSection(
    pnlState: PnlUiState,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = extendedColors.cardSurface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = "P&L",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (pnlState) {
                is PnlUiState.Loading -> {
                    Text(
                        text = "—",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                is PnlUiState.Success -> {
                    val pnl = pnlState.data
                    // Total return prominently displayed with animation
                    if (pnl.totalReturn != null) {
                        AnimatedPnlText(
                            value = pnl.totalReturn,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    // Metrics row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                text = "Rendement",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = pnl.totalReturnPct
                                    ?.let { "%.2f%%".format(it * 100) }
                                    ?: "—",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "Win rate",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = pnl.winRate
                                    ?.let { "%.0f%%".format(it * 100) }
                                    ?: "—",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    // Sharpe ratio
                    if (pnl.sharpeRatio != null) {
                        Text(
                            text = "Sharpe : %.2f".format(pnl.sharpeRatio),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Trades count W/L
                    if (pnl.tradesCount != null && pnl.tradesCount > 0) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(
                                    text = "Trades",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "${pnl.tradesCount}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "Gagnants",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "${pnl.winningTrades ?: 0}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = extendedColors.pnlPositive,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "Perdants",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "${pnl.losingTrades ?: 0}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = extendedColors.pnlNegative,
                                )
                            }
                        }
                    }
                }
                is PnlUiState.Error -> {
                    Text(
                        text = "Indisponible",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuoteSection(
    quoteState: QuoteUiState,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = extendedColors.cardSurface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            when (quoteState) {
                is QuoteUiState.Loading -> {
                    Text(
                        text = "Cours — chargement…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is QuoteUiState.Success -> {
                    QuoteContent(
                        symbol = quoteState.data.symbol,
                        quoteData = quoteState.data,
                    )
                }
                is QuoteUiState.Stale -> {
                    // Pass Quote directly — QuoteContent skips recomposition when
                    // the underlying Quote data is structurally equal (data class),
                    // even if the wrapper changed from Success to Stale.
                    QuoteContent(
                        symbol = quoteState.data.symbol,
                        quoteData = quoteState.data,
                    )
                    // Stale indicator
                    Text(
                        text = "VPN inactif — cours non actualisé",
                        style = MaterialTheme.typography.labelSmall,
                        color = extendedColors.warning,
                    )
                    val syncedAt = quoteState.data.timestamp.toEpochMilli()
                    CacheTimestamp(syncedAt = syncedAt)
                }
                is QuoteUiState.Error -> {
                    Text(
                        text = "Cours indisponible",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * Displays quote price and change. Accepts [Quote] directly (not [QuoteUiState])
 * so that Compose structural equality on the data class parameters prevents
 * recomposition when the state wrapper changes (Success → Stale) but the
 * underlying data is identical.
 */
@Composable
private fun QuoteContent(
    symbol: String,
    quoteData: Quote,
    modifier: Modifier = Modifier,
) {
    // Memoize the formatted timestamp — only recompute when the Instant changes.
    // ZoneId.systemDefault() and DateTimeFormatter allocation are avoided on
    // recompositions where only price/change changed (same timestamp).
    val formattedTimestamp = remember(quoteData.timestamp) {
        quoteData.timestamp
            .atZone(ZoneId.systemDefault())
            .let { DateTimeFormatter.ofPattern("HH:mm:ss").format(it) }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = symbol,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = formattedTimestamp,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            MoneyText(
                amount = quoteData.price,
                decimals = 2,
                style = MaterialTheme.typography.titleMedium,
            )
            AnimatedPnlText(
                value = quoteData.change,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
