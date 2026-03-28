package com.tradingplatform.app.ui.screens.portfolio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradingplatform.app.domain.model.Position
import com.tradingplatform.app.domain.model.PositionStatus
import com.tradingplatform.app.ui.components.CacheTimestamp
import com.tradingplatform.app.ui.components.ClosedPositionBadge
import com.tradingplatform.app.ui.components.EmptyPositionsIllustration
import com.tradingplatform.app.ui.components.EmptyState
import com.tradingplatform.app.ui.components.MoneyText
import com.tradingplatform.app.ui.components.OpenPositionBadge
import com.tradingplatform.app.ui.components.AnimatedPnlText
import com.tradingplatform.app.ui.components.SkeletonPositionCard
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing
import com.tradingplatform.app.ui.theme.pnlColor
import java.math.BigDecimal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PositionsScreen(
    onNavigateToDetail: (positionId: Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PositionsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedFilter by viewModel.selectedFilter.collectAsStateWithLifecycle()

    val isRefreshing = uiState is PositionsUiState.Loading

    // Snackbar for errors
    val snackbarHostState = remember { SnackbarHostState() }
    val errorMessage = (uiState as? PositionsUiState.Error)?.message

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
            TopAppBar(title = { Text("Positions") })
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        modifier = modifier,
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing && uiState !is PositionsUiState.Loading,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val state = uiState) {
                is PositionsUiState.Loading -> {
                    // Skeleton loading
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                        contentPadding = PaddingValues(Spacing.lg),
                    ) {
                        items(5) {
                            SkeletonPositionCard()
                        }
                    }
                }
                is PositionsUiState.Success -> {
                    if (state.positions.isEmpty()) {
                        val emptyTitle = when (selectedFilter) {
                            StatusFilter.OPEN -> "Aucune position ouverte"
                            StatusFilter.CLOSED -> "Aucune position fermée"
                            StatusFilter.ALL -> "Aucune position"
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(Spacing.lg),
                            contentAlignment = Alignment.Center,
                        ) {
                            EmptyState(
                                illustration = { EmptyPositionsIllustration() },
                                title = emptyTitle,
                                message = "Vos positions apparaîtront ici lorsque des trades seront exécutés.",
                            )
                        }
                    } else {
                        PositionsList(
                            positions = state.positions,
                            syncedAt = state.syncedAt,
                            selectedFilter = selectedFilter,
                            onFilterSelect = { viewModel.selectFilter(it) },
                            onNavigateToDetail = onNavigateToDetail,
                        )
                    }
                }
                is PositionsUiState.Error -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(Spacing.lg),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyState(
                            illustration = { EmptyPositionsIllustration() },
                            title = "Impossible de charger",
                            message = state.message,
                            actionLabel = "Réessayer",
                            onAction = { viewModel.refresh() },
                        )
                    }
                }
            }
        }
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun PositionsList(
    positions: List<Position>,
    syncedAt: Long,
    selectedFilter: StatusFilter,
    onFilterSelect: (StatusFilter) -> Unit,
    onNavigateToDetail: (positionId: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        contentPadding = PaddingValues(Spacing.lg),
    ) {
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                StatusFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = filter == selectedFilter,
                        onClick = { onFilterSelect(filter) },
                        label = { Text(filter.label) },
                    )
                }
            }
        }
        item {
            CacheTimestamp(syncedAt = syncedAt)
        }
        items(positions, key = { it.id }) { position ->
            PositionCard(
                position = position,
                onClick = { onNavigateToDetail(position.id) },
            )
        }
    }
}

/**
 * Position card with improved visual hierarchy:
 * - P&L is the most prominent element (larger, bolder)
 * - Symbol is the primary identifier with badge inline
 * - Price and quantity are secondary
 */
@Composable
private fun PositionCard(
    position: Position,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "Position ${position.symbol}, " +
                    "quantité ${position.quantity}, " +
                    if (position.status == PositionStatus.OPEN) "ouverte" else "fermée"
            },
        colors = CardDefaults.cardColors(
            containerColor = LocalExtendedColors.current.cardSurface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            // Left: symbol (prominent) + quantity + badge
            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Text(
                        text = position.symbol,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (position.status == PositionStatus.OPEN) {
                        OpenPositionBadge()
                    } else {
                        ClosedPositionBadge()
                    }
                }
                Text(
                    text = "Qté : ${position.quantity.stripTrailingZeros().toPlainString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Right: P&L as the primary visual element
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                // P&L amount — most prominent, animated for live WS updates
                AnimatedPnlText(
                    value = position.unrealizedPnl ?: BigDecimal.ZERO,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                )
                // P&L percentage
                val pnlColor = pnlColor(position.unrealizedPnl ?: BigDecimal.ZERO)
                val pnlPct = position.unrealizedPnlPercent ?: 0.0
                val formattedPct = "${if (pnlPct >= 0) "+" else ""}${"%.2f".format(pnlPct)}%"
                Text(
                    text = formattedPct,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Medium,
                    ),
                    color = pnlColor,
                    modifier = Modifier.semantics {
                        contentDescription = "Rendement : $formattedPct"
                    },
                )
                // Current price — secondary
                MoneyText(
                    amount = position.currentPrice ?: BigDecimal.ZERO,
                    decimals = 2,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
