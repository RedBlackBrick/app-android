package com.tradingplatform.app.ui.screens.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradingplatform.app.domain.model.Order
import com.tradingplatform.app.domain.model.OrderSide
import com.tradingplatform.app.domain.model.OrderStatus
import com.tradingplatform.app.ui.components.TradingCard
import com.tradingplatform.app.ui.components.ConfirmActionSheet
import com.tradingplatform.app.ui.components.MoneyText
import com.tradingplatform.app.ui.components.PortfolioSegment
import com.tradingplatform.app.ui.components.PortfolioSegmentedTabs
import com.tradingplatform.app.ui.components.PortfolioSwitcher
import com.tradingplatform.app.ui.components.SkeletonPositionCard
import com.tradingplatform.app.ui.theme.IconSize
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())

/** Cible tactile minimale (Material / TalkBack). */
private val MinTouchTarget = 48.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(
    onSelectSegment: (PortfolioSegment) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: OrdersViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Messages de l'annulation (garde bloquée, demande envoyée, résultat de la relecture, échec).
    val snackbarHostState = remember { SnackbarHostState() }
    val message = uiState.message
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Long)
            viewModel.onMessageShown(message)
        }
    }

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
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            PortfolioSegmentedTabs(
                selected = PortfolioSegment.Orders,
                onSelect = onSelectSegment,
            )
            PrimaryTabRow(selectedTabIndex = uiState.selectedTab.ordinal) {
                Tab(
                    selected = uiState.selectedTab == OrdersTab.ACTIVE,
                    onClick = { viewModel.selectTab(OrdersTab.ACTIVE) },
                    text = { Text("Actifs") },
                )
                Tab(
                    selected = uiState.selectedTab == OrdersTab.HISTORY,
                    onClick = { viewModel.selectTab(OrdersTab.HISTORY) },
                    // « Terminés » et pas « Historique » : c'est déjà le libellé du segment
                    // Transactions juste au-dessus.
                    text = { Text("Terminés") },
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
                OrdersContent(
                    state = tabState,
                    onLoadMore = if (uiState.selectedTab == OrdersTab.HISTORY) {
                        viewModel::loadMoreHistory
                    } else {
                        null
                    },
                    // Annulation : onglet « Actifs » uniquement ; une seule à la fois.
                    onCancel = if (uiState.selectedTab == OrdersTab.ACTIVE) {
                        viewModel::onCancelClicked
                    } else {
                        null
                    },
                    cancelEnabled = uiState.cancelInFlightId == null,
                    cancelRequestedIds = uiState.cancelRequestedIds,
                )
            }
        }
    }

    // Récapitulatif destructif puis biométrie à chaque annulation (docs/write-actions.md) ;
    // la demande n'est envoyée que depuis le succès du prompt (onConfirmed).
    val confirmAction = remember(uiState.pendingCancel) {
        uiState.pendingCancel?.let(::cancelConfirmAction)
    }
    ConfirmActionSheet(
        action = confirmAction,
        onDismiss = viewModel::dismissCancel,
        onConfirmed = { viewModel.confirmCancel() },
    )
}

@Composable
private fun OrdersContent(
    state: OrdersTabState,
    onLoadMore: (() -> Unit)? = null,
    onCancel: ((orderId: Long) -> Unit)? = null,
    cancelEnabled: Boolean = true,
    cancelRequestedIds: Set<Long> = emptySet(),
) {
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
                        val cancelRequested = order.id in cancelRequestedIds
                        // Bouton seulement pour un statut annulable et pas déjà « demandé ».
                        val cancelAction: (() -> Unit)? =
                            if (onCancel != null && !cancelRequested && order.status.isCancellable()) {
                                ({ onCancel(order.id) })
                            } else {
                                null
                            }
                        OrderRow(
                            order = order,
                            cancelRequested = cancelRequested,
                            onCancel = cancelAction,
                            cancelEnabled = cancelEnabled,
                        )
                    }
                    if (onLoadMore != null && state.hasMore) {
                        item {
                            OutlinedButton(
                                onClick = onLoadMore,
                                enabled = !state.isLoadingMore,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (state.isLoadingMore) {
                                    CircularProgressIndicator(modifier = Modifier.size(IconSize.sm))
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

@Composable
private fun OrderRow(
    order: Order,
    cancelRequested: Boolean = false,
    onCancel: (() -> Unit)? = null,
    cancelEnabled: Boolean = true,
) {
    val extendedColors = LocalExtendedColors.current
    val sideLabel = orderSideLabel(order.side)
    val sideColor = when (order.side) {
        OrderSide.BUY -> extendedColors.pnlPositive
        OrderSide.SELL -> extendedColors.pnlNegative
    }
    // Annulation demandée et pas encore résolue par une relecture : on ne dit jamais « annulé ».
    val statusLabel = if (cancelRequested) {
        CANCEL_REQUESTED_LABEL
    } else {
        order.status?.displayLabel() ?: "—"
    }
    val timestamp = order.updatedAt ?: order.createdAt
    val formattedTime = timestamp?.let(timeFormatter::format) ?: "—"

    TradingCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Ordre $sideLabel ${order.symbol}, statut $statusLabel"
            },
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
                    if (cancelRequested) {
                        // Pastille « en cours » (texte + fond : jamais la seule couleur).
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = extendedColors.onWarningContainer,
                            modifier = Modifier
                                .background(extendedColors.warningContainer, RoundedCornerShape(50))
                                .padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
                        )
                    } else {
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = order.status.statusColor(extendedColors),
                        )
                    }
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
            if (onCancel != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    CancelOrderButton(
                        description = cancelButtonDescription(order),
                        enabled = cancelEnabled,
                        onClick = onCancel,
                    )
                }
            }
        }
    }
}

/**
 * Action discrète « Annuler » (texte, couleur `error` : action destructive), cible ≥ 48 dp.
 * Désactivée pendant qu'une autre annulation est en vol (pas de double envoi).
 */
@Composable
private fun CancelOrderButton(
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = modifier
            .heightIn(min = MinTouchTarget)
            .semantics { contentDescription = description },
    ) {
        Text(
            text = "Annuler",
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun CenteredLoading() {
    // Squelettes (même gabarit que les cartes d'ordres) plutôt qu'un spinner nu.
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items(4) { SkeletonPositionCard() }
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
