package com.tradingplatform.app.ui.screens.market

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradingplatform.app.domain.model.Quote
import com.tradingplatform.app.ui.components.AnimatedPnlText
import com.tradingplatform.app.ui.components.MoneyText
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing
import com.tradingplatform.app.ui.theme.pnlColor
import java.math.BigDecimal
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketDataScreen(
    modifier: Modifier = Modifier,
    viewModel: MarketDataViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val symbolPickerState by viewModel.symbolPickerState.collectAsStateWithLifecycle()

    var showSymbolPicker by remember { mutableStateOf(false) }

    if (showSymbolPicker) {
        val watchlistSymbols = (uiState as? MarketDataUiState.Success)?.watchlistSymbols
            ?: emptyList()
        SymbolPickerSheet(
            symbolPickerState = symbolPickerState,
            watchlistSymbols = watchlistSymbols,
            onRefresh = { viewModel.refreshSymbols() },
            onAddSymbol = { viewModel.addSymbol(it) },
            onRemoveSymbol = { viewModel.removeSymbol(it) },
            onDismiss = { showSymbolPicker = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("March\u00e9s") },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    viewModel.refreshSymbols()
                    showSymbolPicker = true
                },
                modifier = Modifier.semantics {
                    contentDescription = "Ajouter un symbole"
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                )
            }
        },
        modifier = modifier,
    ) { innerPadding ->
        when (val state = uiState) {
            is MarketDataUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            is MarketDataUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            is MarketDataUiState.Success -> {
                val isRefreshing = false // Quotes update continuously via WS/polling

                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.refresh() },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    if (state.watchlistSymbols.isEmpty()) {
                        EmptyWatchlistState(
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = Spacing.lg,
                                vertical = Spacing.sm,
                            ),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            items(
                                items = state.watchlistSymbols,
                                key = { it },
                            ) { symbol ->
                                SwipeToDismissWatchlistCard(
                                    symbol = symbol,
                                    quote = state.quotes[symbol],
                                    onDismiss = { viewModel.removeSymbol(symbol) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Empty state ──────────────────────────────────────────────────────────────

@Composable
private fun EmptyWatchlistState(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = "Aucun symbole suivi",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Appuyez sur + pour ajouter des symboles",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Swipe-to-dismiss wrapper ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDismissWatchlistCard(
    symbol: String,
    quote: Quote?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDismiss()
                true
            } else {
                false
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val color by animateColorAsState(
                targetValue = if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                animationSpec = tween(),
                label = "swipe_bg_color",
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color, MaterialTheme.shapes.medium)
                    .padding(horizontal = Spacing.lg),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Supprimer $symbol de la watchlist",
                    tint = MaterialTheme.colorScheme.onError,
                )
            }
        },
        enableDismissFromStartToEnd = false,
        modifier = modifier,
    ) {
        WatchlistCard(
            symbol = symbol,
            quote = quote,
        )
    }
}

// ── Watchlist card ───────────────────────────────────────────────────────────

@Composable
private fun WatchlistCard(
    symbol: String,
    quote: Quote?,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = extendedColors.cardSurface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left: symbol + timestamp
            Column {
                Text(
                    text = symbol,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics {
                        contentDescription = "Symbole : $symbol"
                    },
                )
                if (quote != null) {
                    val formattedTimestamp = remember(quote.timestamp) {
                        quote.timestamp
                            .atZone(ZoneId.systemDefault())
                            .let { DateTimeFormatter.ofPattern("HH:mm:ss").format(it) }
                    }
                    Text(
                        text = formattedTimestamp,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "Chargement\u2026",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Right: price + change
            if (quote != null) {
                Column(horizontalAlignment = Alignment.End) {
                    MoneyText(
                        amount = quote.price,
                        decimals = 2,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics {
                            contentDescription = "Prix : ${quote.price} \u20ac"
                        },
                    )
                    ChangePercentText(
                        changePercent = quote.changePercent,
                        change = quote.change,
                    )
                }
            } else {
                Text(
                    text = "\u2014",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Change percent display ──────────────────────────────────────────────────

@Composable
private fun ChangePercentText(
    changePercent: Double,
    change: BigDecimal,
    modifier: Modifier = Modifier,
) {
    val percentFormatted = remember(changePercent) {
        val prefix = if (changePercent > 0) "+" else ""
        "${prefix}${"%.2f".format(changePercent)}%"
    }

    val color = pnlColor(change)

    val verboseDescription = remember(changePercent, change) {
        val label = when {
            change > BigDecimal.ZERO -> "Hausse"
            change < BigDecimal.ZERO -> "Baisse"
            else -> "Variation"
        }
        "$label : $percentFormatted"
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.semantics {
            contentDescription = verboseDescription
        },
    ) {
        AnimatedPnlText(
            value = change,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = percentFormatted,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}
