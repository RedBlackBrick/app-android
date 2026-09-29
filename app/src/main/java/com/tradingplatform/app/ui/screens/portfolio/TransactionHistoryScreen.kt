package com.tradingplatform.app.ui.screens.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradingplatform.app.domain.model.Transaction
import com.tradingplatform.app.ui.components.TradingCard
import com.tradingplatform.app.ui.components.MoneyText
import com.tradingplatform.app.ui.components.PortfolioSegment
import com.tradingplatform.app.ui.components.PortfolioSegmentedTabs
import com.tradingplatform.app.ui.components.PortfolioSwitcher
import com.tradingplatform.app.ui.theme.IconSize
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionHistoryScreen(
    onSelectSegment: (PortfolioSegment) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: TransactionHistoryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Portefeuille") },
                actions = {
                    PortfolioSwitcher()
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Réglages",
                        )
                    }
                },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            PortfolioSegmentedTabs(
                selected = PortfolioSegment.History,
                onSelect = onSelectSegment,
            )
            PullToRefreshBox(
                isRefreshing = uiState is TransactionHistoryUiState.Loading,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                when (val state = uiState) {
                    is TransactionHistoryUiState.Loading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    is TransactionHistoryUiState.Error -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(Spacing.lg),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = state.message,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Spacer(modifier = Modifier.height(Spacing.md))
                                Button(onClick = { viewModel.refresh() }) {
                                    Text("Réessayer")
                                }
                            }
                        }
                    }
                    is TransactionHistoryUiState.Success -> {
                        if (state.transactions.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(Spacing.lg),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "Aucune transaction",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(Spacing.lg),
                                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                            ) {
                                items(state.transactions, key = { it.id }) { tx ->
                                    TransactionCard(transaction = tx)
                                }
                                if (state.hasMore) {
                                    item {
                                        OutlinedButton(
                                            onClick = { viewModel.loadMore() },
                                            enabled = !state.isLoadingMore,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            if (state.isLoadingMore) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(IconSize.sm),
                                                )
                                            } else {
                                                Text("Charger plus")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionCard(
    transaction: Transaction,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current
    val isBuy = transaction.action.uppercase() == "BUY"
    val actionColor = if (isBuy) extendedColors.pnlPositive else extendedColors.pnlNegative
    val actionLabel = if (isBuy) "Achat" else "Vente"

    val formattedDate = remember(transaction.executedAt) {
        transaction.executedAt
            .atZone(ZoneId.systemDefault())
            .let { DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").format(it) }
    }

    TradingCard(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "$actionLabel ${transaction.symbol}, " +
                    "quantité ${transaction.quantity}, prix ${transaction.price}"
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = actionColor,
                    )
                    Text(
                        text = transaction.symbol,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Qté : ${transaction.quantity.stripTrailingZeros().toPlainString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MoneyText(
                    amount = transaction.total,
                    decimals = 2,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
