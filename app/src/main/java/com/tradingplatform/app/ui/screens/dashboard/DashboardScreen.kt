package com.tradingplatform.app.ui.screens.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.ui.components.CacheTimestamp
import com.tradingplatform.app.ui.components.ErrorBanner
import com.tradingplatform.app.ui.components.LoadingOverlay
import com.tradingplatform.app.ui.components.MoneyText
import com.tradingplatform.app.ui.components.PnlText
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToPositions: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val isLoading = uiState.navSummary is NavUiState.Loading &&
        uiState.pnlSummary is PnlUiState.Loading

    val isRefreshing = uiState.pnlSummary is PnlUiState.Loading || uiState.navSummary is NavUiState.Loading

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Dashboard") })
        },
        modifier = modifier,
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                ) {
                    // ── NAV (Net Asset Value) ─────────────────────────────────────────
                    NavSection(navState = uiState.navSummary)

                    // ── P&L period chips ──────────────────────────────────────────────
                    PnlPeriodChips(
                        selected = uiState.selectedPeriod,
                        onSelect = viewModel::selectPeriod,
                    )

                    // ── P&L summary ───────────────────────────────────────────────────
                    PnlSection(pnlState = uiState.pnlSummary)

                    // ── Quote ─────────────────────────────────────────────────────────
                    QuoteSection(quoteState = uiState.quote)

                    // ── Navigation ────────────────────────────────────────────────────
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Button(
                        onClick = onNavigateToPositions,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Voir positions")
                    }
                }

                // ── Error banners ─────────────────────────────────────────────────
                val navError = (uiState.navSummary as? NavUiState.Error)?.message
                val pnlError = (uiState.pnlSummary as? PnlUiState.Error)?.message
                val quoteError = (uiState.quote as? QuoteUiState.Error)?.message
                val errorMessage = navError ?: pnlError ?: quoteError

                if (errorMessage != null) {
                    ErrorBanner(
                        message = errorMessage,
                        onRetry = { viewModel.refresh() },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(Spacing.lg),
                    )
                }

                // ── Loading overlay (initial only) ────────────────────────────────
                if (isLoading) {
                    LoadingOverlay()
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
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
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
                        amount = nav.nav,
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "Valeur liquidative totale : ${nav.nav} €"
                            },
                    )
                    val syncedAt = nav.timestamp.toEpochMilli()
                    CacheTimestamp(syncedAt = syncedAt)
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
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
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
                    // Total P&L prominently displayed
                    PnlText(
                        value = pnl.totalPnl,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Breakdown row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                text = "Réalisé",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            PnlText(
                                value = pnl.realizedPnl,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "Non réalisé",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            PnlText(
                                value = pnl.unrealizedPnl,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    // Trade count
                    Text(
                        text = "${pnl.tradesCount} trades — ${pnl.winningTrades}W / ${pnl.losingTrades}L",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
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
                    val quote = quoteState.data
                    QuoteContent(
                        symbol = quote.symbol,
                        quote = quoteState,
                    )
                }
                is QuoteUiState.Stale -> {
                    val quote = quoteState.data
                    QuoteContent(
                        symbol = quote.symbol,
                        quote = quoteState,
                    )
                    // Stale indicator
                    Text(
                        text = "VPN inactif — cours non actualisé",
                        style = MaterialTheme.typography.labelSmall,
                        color = extendedColors.warning,
                    )
                    val syncedAt = quote.timestamp.toEpochMilli()
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

@Composable
private fun QuoteContent(
    symbol: String,
    quote: QuoteUiState,
    modifier: Modifier = Modifier,
) {
    val quoteData = when (quote) {
        is QuoteUiState.Success -> quote.data
        is QuoteUiState.Stale -> quote.data
        else -> return
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
            val timestamp = quoteData.timestamp
                .atZone(ZoneId.systemDefault())
                .let { DateTimeFormatter.ofPattern("HH:mm:ss").format(it) }
            Text(
                text = timestamp,
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
            PnlText(
                value = quoteData.change,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
