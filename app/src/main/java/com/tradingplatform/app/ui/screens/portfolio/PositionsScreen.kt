package com.tradingplatform.app.ui.screens.portfolio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.tradingplatform.app.domain.model.Position
import com.tradingplatform.app.domain.model.PositionStatus
import com.tradingplatform.app.ui.components.CacheTimestamp
import com.tradingplatform.app.ui.components.ClosedPositionBadge
import com.tradingplatform.app.ui.components.ErrorBanner
import com.tradingplatform.app.ui.components.LoadingOverlay
import com.tradingplatform.app.ui.components.MoneyText
import com.tradingplatform.app.ui.components.OpenPositionBadge
import com.tradingplatform.app.ui.components.PnlText
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PositionsScreen(
    onNavigateToDetail: (positionId: Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PositionsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val isRefreshing = uiState is PositionsUiState.Loading

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Positions") })
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
                when (val state = uiState) {
                    is PositionsUiState.Loading -> {
                        LoadingOverlay()
                    }
                    is PositionsUiState.Success -> {
                        if (state.positions.isEmpty()) {
                            EmptyPositions(
                                syncedAt = state.syncedAt,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(Spacing.lg),
                            )
                        } else {
                            PositionsList(
                                positions = state.positions,
                                syncedAt = state.syncedAt,
                                onNavigateToDetail = onNavigateToDetail,
                            )
                        }
                    }
                    is PositionsUiState.Error -> {
                        ErrorBanner(
                            message = state.message,
                            onRetry = { viewModel.refresh() },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(Spacing.lg),
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
    onNavigateToDetail: (positionId: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.lg),
    ) {
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
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left: symbol + quantity + badge
            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    text = position.symbol,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Qté : ${position.quantity.stripTrailingZeros().toPlainString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (position.status == PositionStatus.OPEN) {
                    OpenPositionBadge()
                } else {
                    ClosedPositionBadge()
                }
            }

            // Right: current price + unrealized P&L
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                MoneyText(
                    amount = position.currentPrice,
                    decimals = 2,
                    style = MaterialTheme.typography.bodyMedium,
                )
                PnlText(
                    value = position.unrealizedPnl,
                    style = MaterialTheme.typography.bodyMedium,
                )
                val pnlColor = when {
                    position.unrealizedPnlPercent > 0.0 -> LocalExtendedColors.current.pnlPositive
                    position.unrealizedPnlPercent < 0.0 -> LocalExtendedColors.current.pnlNegative
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Text(
                    text = "${if (position.unrealizedPnlPercent >= 0) "+" else ""}${"%.2f".format(position.unrealizedPnlPercent)}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = pnlColor,
                )
            }
        }
    }
}

@Composable
private fun EmptyPositions(
    syncedAt: Long,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Aucune position ouverte",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (syncedAt > 0L) {
            CacheTimestamp(syncedAt = syncedAt)
        }
    }
}
