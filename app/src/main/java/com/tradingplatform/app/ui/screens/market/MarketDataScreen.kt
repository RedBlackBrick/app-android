package com.tradingplatform.app.ui.screens.market

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradingplatform.app.domain.model.Quote
import com.tradingplatform.app.ui.components.AnimatedPnlText
import com.tradingplatform.app.ui.components.AnimatedPriceText
import com.tradingplatform.app.ui.components.MoneyText
import com.tradingplatform.app.ui.components.SkeletonQuoteCard
import com.tradingplatform.app.ui.components.SparklineChart
import com.tradingplatform.app.ui.components.rememberHapticFeedback
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing
import com.tradingplatform.app.ui.theme.TradingNumbers
import com.tradingplatform.app.ui.theme.pnlColor
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketDataScreen(
    modifier: Modifier = Modifier,
    viewModel: MarketDataViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val symbolPickerState by viewModel.symbolPickerState.collectAsStateWithLifecycle()
    val haptic = rememberHapticFeedback()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

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
                title = { Text("Marchés") },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    haptic.click()
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
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    items(5) { SkeletonQuoteCard() }
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
                            contentPadding = PaddingValues(
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
                                    sparklinePoints = state.sparklines[symbol],
                                    onDismiss = {
                                        haptic.reject()
                                        viewModel.removeSymbol(symbol)
                                    },
                                    onSourceTap = { message ->
                                        scope.launch {
                                            snackbarHostState.currentSnackbarData?.dismiss()
                                            snackbarHostState.showSnackbar(message)
                                        }
                                    },
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
    sparklinePoints: List<BigDecimal>?,
    onDismiss: () -> Unit,
    onSourceTap: (String) -> Unit = {},
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
            sparklinePoints = sparklinePoints,
            onSourceTap = onSourceTap,
        )
    }
}

// ── Watchlist card ───────────────────────────────────────────────────────────

@Composable
private fun WatchlistCard(
    symbol: String,
    quote: Quote?,
    sparklinePoints: List<BigDecimal>? = null,
    onSourceTap: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = extendedColors.cardSurface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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

                // Right: price + change + source dot
                if (quote != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        ) {
                            SourceQualityDot(
                                quote = quote,
                                onTap = onSourceTap,
                            )
                            AnimatedPriceText(
                                value = quote.price,
                                style = TradingNumbers.titleMedium,
                                modifier = Modifier.semantics {
                                    contentDescription = "Prix : ${quote.price} \u20ac"
                                },
                            )
                        }
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

            // Bid/Ask + Volume row
            if (quote != null) {
                val hasBidAsk = quote.bid != null && quote.ask != null
                val volumeFormatted = remember(quote.volume) {
                    NumberFormat.getNumberInstance(Locale.FRENCH).format(quote.volume)
                }
                if (hasBidAsk || quote.volume > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (hasBidAsk) {
                            Text(
                                text = "Bid: ${quote.bid} / Ask: ${quote.ask}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.semantics {
                                    contentDescription = "Bid : ${quote.bid} euros, Ask : ${quote.ask} euros"
                                },
                            )
                        }
                        if (quote.volume > 0) {
                            Text(
                                text = "Vol: $volumeFormatted",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.semantics {
                                    contentDescription = "Volume : $volumeFormatted"
                                },
                            )
                        }
                    }
                }
            }

            // Sparkline mini-chart
            if (!sparklinePoints.isNullOrEmpty() && sparklinePoints.size >= 2) {
                SparklineChart(
                    dataPoints = sparklinePoints,
                    modifier = Modifier.fillMaxWidth(),
                    height = Spacing.xxxl,
                )
            }
        }
    }
}

// ── Source quality dot ──────────────────────────────────────────────────────

/**
 * Petit point coloré (6dp) indiquant le mode de données de la source.
 *
 * - Vert  : temps réel (`"realtime"`)
 * - Ambre : polling / différé (`"polling"`)
 * - Gris  : fin de journée ou inconnu
 *
 * Un tap affiche un snackbar avec le détail de la source et la qualité.
 */
@Composable
private fun SourceQualityDot(
    quote: Quote,
    onTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dotColor = when (quote.dataMode) {
        "realtime" -> Color(0xFF22C55E)  // green-500
        "polling" -> Color(0xFFF59E0B)   // amber-500
        else -> Color(0xFF9CA3AF)        // gray-400
    }

    val tooltipMessage = remember(quote.sourceName, quote.dataMode, quote.quality) {
        buildSourceTooltip(quote)
    }

    Box(
        modifier = modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(dotColor)
            .clickable { onTap(tooltipMessage) }
            .semantics {
                contentDescription = tooltipMessage
            },
    )
}

/**
 * Construit le message tooltip pour la source d'un quote.
 *
 * Exemples :
 * - "Temps réel via Investing.com (q=82)"
 * - "Différé ~60s via Yahoo (q=70)"
 * - "Fin de journée via Stooq"
 * - "Source inconnue"
 */
private fun buildSourceTooltip(quote: Quote): String {
    val modeLabel = when (quote.dataMode) {
        "realtime" -> "Temps réel"
        "polling" -> "Différé ~60s"
        "eod" -> "Fin de journée"
        else -> null
    }

    val sourcePart = quote.sourceName?.replaceFirstChar { it.uppercase() }
    val qualityPart = quote.quality?.let { " (q=$it)" } ?: ""

    return when {
        modeLabel != null && sourcePart != null -> "$modeLabel via $sourcePart$qualityPart"
        modeLabel != null -> "$modeLabel$qualityPart"
        sourcePart != null -> "Via $sourcePart$qualityPart"
        else -> "Source inconnue"
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
