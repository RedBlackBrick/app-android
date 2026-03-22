package com.tradingplatform.app.ui.screens.performance

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradingplatform.app.domain.model.PerformanceMetrics
import com.tradingplatform.app.ui.components.ErrorBanner
import com.tradingplatform.app.ui.components.MoneyText
import com.tradingplatform.app.ui.components.SkeletonDashboardCard
import com.tradingplatform.app.ui.components.formatMoneyAmount
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
        // ── Section 1: Metriques de risque ──────────────────────────────────
        SectionHeader(title = "Metriques de risque")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            MetricCard(
                label = "Ratio de Sharpe",
                value = metrics.sharpeRatio?.let { "%.2f".format(it) },
                accessibilityLabel = "Ratio de Sharpe",
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                label = "Ratio de Sortino",
                value = metrics.sortinoRatio?.let { "%.2f".format(it) },
                accessibilityLabel = "Ratio de Sortino",
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            MetricCard(
                label = "Drawdown max",
                value = metrics.maxDrawdown?.let { "%.2f%%".format(it * 100) },
                accessibilityLabel = "Drawdown maximum",
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                label = "Volatilite",
                value = metrics.volatility?.let { "%.2f%%".format(it * 100) },
                accessibilityLabel = "Volatilite annualisee",
                modifier = Modifier.weight(1f),
            )
        }

        // ── Section 2: Rendements ───────────────────────────────────────────
        SectionHeader(title = "Rendements")
        MetricCard(
            label = "Rendement total",
            value = metrics.totalReturnPct?.let { "%+.2f%%".format(it * 100) },
            accessibilityLabel = "Rendement total en pourcentage",
            modifier = Modifier.fillMaxWidth(),
        )
        if (metrics.totalReturn != null) {
            TotalReturnCard(
                totalReturn = metrics.totalReturn,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            MetricCard(
                label = "CAGR",
                value = metrics.cagr?.let { "%.2f%%".format(it * 100) },
                accessibilityLabel = "Taux de croissance annuel compose",
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                label = "Rendement moyen",
                value = metrics.avgTradeReturn?.let {
                    formatMoneyAmount(it, "EUR")
                },
                accessibilityLabel = "Rendement moyen par trade",
                modifier = Modifier.weight(1f),
            )
        }

        // ── Section 3: Statistiques de trading ──────────────────────────────
        SectionHeader(title = "Statistiques de trading")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            MetricCard(
                label = "Win rate",
                value = metrics.winRate?.let { "%.0f%%".format(it * 100) },
                accessibilityLabel = "Taux de trades gagnants",
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                label = "Profit factor",
                value = metrics.profitFactor?.let { "%.2f".format(it) },
                accessibilityLabel = "Facteur de profit",
                modifier = Modifier.weight(1f),
            )
        }
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

// ── Metric card ─────────────────────────────────────────────────────────────────

@Composable
private fun MetricCard(
    label: String,
    value: String?,
    accessibilityLabel: String,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current
    val displayValue = value ?: "—"
    val description = if (value != null) "$accessibilityLabel : $displayValue" else "$accessibilityLabel : non disponible"

    Card(
        modifier = modifier.semantics {
            contentDescription = description
        },
        colors = CardDefaults.cardColors(
            containerColor = extendedColors.cardSurface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = displayValue,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Start,
            )
        }
    }
}

// ── Total return card with MoneyText ────────────────────────────────────────────

@Composable
private fun TotalReturnCard(
    totalReturn: BigDecimal,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current

    Card(
        modifier = modifier.semantics {
            contentDescription = "Rendement total absolu : $totalReturn EUR"
        },
        colors = CardDefaults.cardColors(
            containerColor = extendedColors.cardSurface,
        ),
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
            MoneyText(
                amount = totalReturn,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
