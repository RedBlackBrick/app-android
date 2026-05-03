package com.tradingplatform.app.ui.screens.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
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
import com.tradingplatform.app.domain.model.Order
import com.tradingplatform.app.domain.model.OrderSide
import com.tradingplatform.app.domain.model.OrderStatus
import com.tradingplatform.app.ui.components.MoneyText
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm").withZone(ZoneId.systemDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OrdersViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ordres") },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            PrimaryTabRow(selectedTabIndex = uiState.selectedTab.ordinal) {
                Tab(
                    selected = uiState.selectedTab == OrdersTab.ACTIVE,
                    onClick = { viewModel.selectTab(OrdersTab.ACTIVE) },
                    text = { Text("Actifs") },
                )
                Tab(
                    selected = uiState.selectedTab == OrdersTab.HISTORY,
                    onClick = { viewModel.selectTab(OrdersTab.HISTORY) },
                    text = { Text("Historique") },
                )
            }

            val tabState = when (uiState.selectedTab) {
                OrdersTab.ACTIVE -> uiState.active
                OrdersTab.HISTORY -> uiState.history
            }

            PullToRefreshBox(
                isRefreshing = tabState is OrdersTabState.Loading,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                OrdersContent(state = tabState)
            }
        }
    }
}

@Composable
private fun OrdersContent(state: OrdersTabState) {
    when (state) {
        is OrdersTabState.Loading -> CenteredLoading()
        is OrdersTabState.Error -> CenteredMessage(text = state.message, isError = true)
        is OrdersTabState.Success -> {
            if (state.orders.isEmpty()) {
                CenteredMessage(text = "Aucun ordre")
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    items(state.orders, key = { it.id }) { order ->
                        OrderRow(order = order)
                    }
                }
            }
        }
    }
}

@Composable
private fun OrderRow(order: Order) {
    val extendedColors = LocalExtendedColors.current
    val sideLabel = when (order.side) {
        OrderSide.BUY -> "Achat"
        OrderSide.SELL -> "Vente"
    }
    val sideColor = when (order.side) {
        OrderSide.BUY -> extendedColors.pnlPositive
        OrderSide.SELL -> extendedColors.pnlNegative
    }
    val statusLabel = order.status?.displayLabel() ?: "—"
    val timestamp = order.updatedAt ?: order.createdAt
    val formattedTime = timestamp?.let(timeFormatter::format) ?: "—"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Ordre $sideLabel ${order.symbol}, statut $statusLabel"
            },
        colors = CardDefaults.cardColors(containerColor = extendedColors.cardSurface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = order.symbol,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = sideLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = sideColor,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = order.status.statusColor(extendedColors),
                    )
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = extendedColors.divider)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "Quantité",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = order.quantity?.toPlainString() ?: "—",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Column {
                    Text(
                        text = "Rempli",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = order.filledQuantity.toPlainString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Prix moyen",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val price = order.averageFillPrice ?: order.limitPrice
                    if (price != null) {
                        MoneyText(
                            amount = price,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Text(
                            text = "—",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredLoading() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun CenteredMessage(text: String, isError: Boolean = false) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(Spacing.xxl))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun OrderStatus.displayLabel(): String = when (this) {
    OrderStatus.PENDING_APPROVAL -> "En attente d'approbation"
    OrderStatus.PENDING -> "En attente"
    OrderStatus.SUBMITTED -> "Soumis"
    OrderStatus.PARTIAL -> "Partiel"
    OrderStatus.FILLED -> "Exécuté"
    OrderStatus.CANCELLED -> "Annulé"
    OrderStatus.REJECTED -> "Rejeté"
    OrderStatus.EXPIRED -> "Expiré"
    OrderStatus.ERROR -> "Erreur"
    OrderStatus.PENDING_CANCEL -> "Annulation en cours"
    OrderStatus.ROLLOVER_PENDING -> "Rollover"
    OrderStatus.PENDING_RETRY -> "Retry"
    OrderStatus.UNKNOWN -> "—"
}

@Composable
private fun OrderStatus?.statusColor(
    extended: com.tradingplatform.app.ui.theme.ExtendedColors,
): androidx.compose.ui.graphics.Color {
    return when (this) {
        OrderStatus.FILLED -> extended.pnlPositive
        OrderStatus.REJECTED, OrderStatus.ERROR, OrderStatus.EXPIRED -> extended.pnlNegative
        OrderStatus.CANCELLED, OrderStatus.PENDING_CANCEL -> MaterialTheme.colorScheme.onSurfaceVariant
        OrderStatus.PARTIAL, OrderStatus.PENDING_RETRY, OrderStatus.ROLLOVER_PENDING ->
            extended.warning
        else -> MaterialTheme.colorScheme.onSurface
    }
}
