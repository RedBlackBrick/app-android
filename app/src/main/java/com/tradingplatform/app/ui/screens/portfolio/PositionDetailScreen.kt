package com.tradingplatform.app.ui.screens.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.tradingplatform.app.domain.model.Transaction
import com.tradingplatform.app.ui.components.CacheTimestamp
import com.tradingplatform.app.ui.components.ClosedPositionBadge
import com.tradingplatform.app.ui.components.ErrorBanner
import com.tradingplatform.app.ui.components.MoneyText
import com.tradingplatform.app.ui.components.OpenPositionBadge
import com.tradingplatform.app.ui.components.PnlText
import com.tradingplatform.app.ui.components.SkeletonPositionDetail
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing
import com.tradingplatform.app.ui.theme.pnlColor
import java.math.BigDecimal
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PositionDetailScreen(
    positionId: Int,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PositionDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val title = when (val state = uiState) {
        is PositionDetailUiState.Success -> state.position.symbol
        else -> "Détail"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val state = uiState) {
                is PositionDetailUiState.Loading -> {
                    SkeletonPositionDetail()
                }
                is PositionDetailUiState.Success -> {
                    PositionDetailContent(
                        position = state.position,
                        transactions = state.transactions,
                        syncedAt = state.syncedAt,
                    )
                }
                is PositionDetailUiState.Error -> {
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

// ── Detail content ────────────────────────────────────────────────────────────

@Composable
private fun PositionDetailContent(
    position: Position,
    transactions: List<Transaction>,
    syncedAt: Long,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.lg),
    ) {
        item {
            PositionSummaryCard(position = position, syncedAt = syncedAt)
        }
        item {
            Text(
                text = "Transactions récentes",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (transactions.isEmpty()) {
            item {
                Text(
                    text = "Aucune transaction",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(transactions, key = { it.id }) { transaction ->
                TransactionRow(transaction = transaction)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun PositionSummaryCard(
    position: Position,
    syncedAt: Long,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Position ${position.symbol} — " +
                    "prix moyen ${position.avgPrice} €, " +
                    "prix actuel ${position.currentPrice} €"
            },
        colors = CardDefaults.cardColors(
            containerColor = LocalExtendedColors.current.cardSurface,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            // Header row: symbol + status badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = position.symbol,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (position.status == PositionStatus.OPEN) {
                    OpenPositionBadge()
                } else {
                    ClosedPositionBadge()
                }
            }

            HorizontalDivider()

            // Price row: avg price vs current price
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                LabeledValue(
                    label = "Prix moyen",
                    content = {
                        MoneyText(
                            amount = position.avgPrice,
                            decimals = 2,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                )
                LabeledValue(
                    label = "Prix actuel",
                    horizontalAlignment = Alignment.End,
                    content = {
                        MoneyText(
                            amount = position.currentPrice ?: BigDecimal.ZERO,
                            decimals = 2,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                )
            }

            // Quantity
            LabeledValue(
                label = "Quantité",
                content = {
                    Text(
                        text = position.quantity.stripTrailingZeros().toPlainString(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
            )

            // Unrealized P&L
            LabeledValue(
                label = "P&L non réalisé",
                content = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PnlText(
                            value = position.unrealizedPnl ?: BigDecimal.ZERO,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        val pnlPct = position.unrealizedPnlPercent ?: 0.0
                        val pnlPctColor = pnlColor(position.unrealizedPnl ?: BigDecimal.ZERO)
                        val formattedPct = "(${if (pnlPct >= 0) "+" else ""}${"%.2f".format(pnlPct)}%)"
                        Text(
                            text = formattedPct,
                            style = MaterialTheme.typography.bodySmall,
                            color = pnlPctColor,
                            modifier = Modifier.semantics {
                                contentDescription = "Rendement : $formattedPct"
                            },
                        )
                    }
                },
            )

            CacheTimestamp(syncedAt = syncedAt)
        }
    }
}

@Composable
private fun LabeledValue(
    label: String,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@Composable
private fun TransactionRow(
    transaction: Transaction,
    modifier: Modifier = Modifier,
) {
    val dateFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    val executedAt = transaction.executedAt
        .atZone(ZoneId.systemDefault())
        .let { dateFormatter.format(it) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm)
            .semantics {
                contentDescription = "Transaction ${transaction.action} " +
                    "${transaction.quantity} ${transaction.symbol} " +
                    "à ${transaction.price} € le $executedAt"
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = transaction.action.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = when (transaction.action.uppercase()) {
                    "BUY" -> MaterialTheme.colorScheme.primary
                    "SELL" -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = executedAt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            MoneyText(
                amount = transaction.price,
                decimals = 2,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Qté : ${transaction.quantity.stripTrailingZeros().toPlainString()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
