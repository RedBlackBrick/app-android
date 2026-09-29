package com.tradingplatform.app.ui.screens.performance

import com.tradingplatform.app.ui.common.formatFr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradingplatform.app.domain.model.PerformanceMetrics
import com.tradingplatform.app.ui.components.ErrorBanner
import com.tradingplatform.app.ui.components.PnlText
import com.tradingplatform.app.ui.components.SkeletonDashboardCard
import com.tradingplatform.app.ui.components.TradingCard
import com.tradingplatform.app.ui.components.buildPnlDescription
import com.tradingplatform.app.ui.components.formatPnlAmount
import com.tradingplatform.app.ui.screens.dashboard.NO_VALUE
import com.tradingplatform.app.ui.screens.dashboard.PnlTone
import com.tradingplatform.app.ui.screens.dashboard.formatPercent
import com.tradingplatform.app.ui.screens.dashboard.percentTone
import com.tradingplatform.app.ui.screens.dashboard.spokenPercent
import com.tradingplatform.app.ui.theme.asNumeric
import com.tradingplatform.app.ui.theme.pnlColor
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing
import java.math.BigDecimal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PerformanceViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val isRefreshing = uiState is PerformanceUiState.Loading

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Performance") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Retour",
                        )
                    }
                },
            )
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
            when (val state = uiState) {
                is PerformanceUiState.Loading -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                    ) {
                        SkeletonDashboardCard()
                        SkeletonDashboardCard()
                        SkeletonDashboardCard()
                    }
                }

                is PerformanceUiState.Success -> {
                    PerformanceContent(
                        metrics = state.metrics,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                is PerformanceUiState.Error -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        ErrorBanner(
                            message = state.message,
                            onRetry = { viewModel.refresh() },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }
    }
}

// ── Content ─────────────────────────────────────────────────────────────────────

/**
 * Ordre pensé « coup d'œil » : d'abord ce que le compte a rapporté, puis le risque pris, puis les
 * statistiques de trading. Toutes les valeurs sont en mono + chiffres tabulaires (`asNumeric`).
 */
@Composable
private fun PerformanceContent(
    metrics: PerformanceMetrics,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        // ── Section 1 : Rendement ───────────────────────────────────────────
        SectionHeader(title = "Rendement")
        ReturnCard(
            totalReturn = metrics.totalReturn,
            totalReturnPct = metrics.totalReturnPct,
            modifier = Modifier.fillMaxWidth(),
        )
        MetricPair(
            first = {
                MetricCard(
                    label = "CAGR",
                    value = metrics.cagr?.let { formatFr("%.2f%%", it * 100) },
                    accessibilityLabel = "Taux de croissance annuel composé",
                    modifier = it,
                )
            },
            second = {
                MetricCard(
                    label = "Gain moyen / trade",
                    value = metrics.avgTradeReturn?.let { formatPnlAmount(it, "€") },
                    accessibilityLabel = "Gain moyen par trade",
                    valueColor = metrics.avgTradeReturn?.let { pnlColor(it) },
                    modifier = it,
                )
            },
        )

        // ── Section 2 : Risque ──────────────────────────────────────────────
        SectionHeader(title = "Risque")
        MetricPair(
            first = {
                MetricCard(
                    label = "Ratio de Sharpe",
                    value = metrics.sharpeRatio?.let { formatFr("%.2f", it) },
                    accessibilityLabel = "Ratio de Sharpe",
                    modifier = it,
                )
            },
            second = {
                MetricCard(
                    label = "Ratio de Sortino",
                    value = metrics.sortinoRatio?.let { formatFr("%.2f", it) },
                    accessibilityLabel = "Ratio de Sortino",
                    modifier = it,
                )
            },
        )
        MetricPair(
            first = {
                MetricCard(
                    label = "Drawdown max",
                    value = metrics.maxDrawdown?.let { formatFr("%.2f%%", it * 100) },
                    accessibilityLabel = "Drawdown maximum",
                    modifier = it,
                )
            },
            second = {
                MetricCard(
                    label = "Volatilité",
                    value = metrics.volatility?.let { formatFr("%.2f%%", it * 100) },
                    accessibilityLabel = "Volatilité annualisée",
                    modifier = it,
                )
            },
        )

        // ── Section 3 : Statistiques de trading ─────────────────────────────
        SectionHeader(title = "Trading")
        MetricPair(
            first = {
                MetricCard(
                    label = "Win rate",
                    value = metrics.winRate?.let { formatFr("%.0f%%", it * 100) },
                    accessibilityLabel = "Taux de trades gagnants",
                    modifier = it,
                )
            },
            second = {
                MetricCard(
                    label = "Profit factor",
                    value = metrics.profitFactor?.let { formatFr("%.2f", it) },
                    accessibilityLabel = "Facteur de profit",
                    modifier = it,
                )
            },
        )
    }
}

// ── Reusable section header ─────────────────────────────────────────────────────

@Composable
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

/** Deux cartes de même largeur côte à côte ; [first]/[second] reçoivent leur `Modifier.weight(1f)`. */
@Composable
private fun MetricPair(
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        first(Modifier.weight(1f))
        second(Modifier.weight(1f))
    }
}

// ── Metric card ─────────────────────────────────────────────────────────────────

@Composable
private fun MetricCard(
    label: String,
    value: String?,
    accessibilityLabel: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
) {
    val displayValue = value ?: NO_VALUE
    val description = if (value != null) "$accessibilityLabel : $displayValue" else "$accessibilityLabel : non disponible"

    TradingCard(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = description
        },
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = displayValue,
                style = MaterialTheme.typography.titleLarge.asNumeric(),
                color = valueColor ?: MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Start,
                maxLines = 1,
            )
        }
    }
}

// ── Rendement total : pourcentage + montant dans une seule carte ────────────────

@Composable
private fun ReturnCard(
    totalReturn: BigDecimal?,
    totalReturnPct: Double?,
    modifier: Modifier = Modifier,
) {
    val spoken = buildList {
        if (totalReturnPct != null) add(spokenPercent(totalReturnPct, signed = true))
        if (totalReturn != null) add(buildPnlDescription(totalReturn, "€"))
    }.joinToString(", ").ifEmpty { "non disponible" }

    TradingCard(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "Rendement total : $spoken"
        },
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = "Rendement total",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatPercent(totalReturnPct, signed = true),
                    style = MaterialTheme.typography.headlineMedium.asNumeric(),
                    color = when (percentTone(totalReturnPct)) {
                        PnlTone.POSITIVE -> LocalExtendedColors.current.pnlPositive
                        PnlTone.NEGATIVE -> LocalExtendedColors.current.pnlNegative
                        PnlTone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
                    },
                )
                if (totalReturn != null) {
                    PnlText(
                        value = totalReturn,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}
