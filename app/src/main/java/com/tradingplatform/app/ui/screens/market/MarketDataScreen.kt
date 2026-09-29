package com.tradingplatform.app.ui.screens.market

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradingplatform.app.domain.model.Quote
import com.tradingplatform.app.ui.components.AnimatedPriceText
import com.tradingplatform.app.ui.components.ErrorBanner
import com.tradingplatform.app.ui.components.SkeletonQuoteCard
import com.tradingplatform.app.ui.components.SparklineChart
import com.tradingplatform.app.ui.components.TradingCard
import com.tradingplatform.app.ui.components.formatPnlAmount
import com.tradingplatform.app.ui.components.rememberHapticFeedback
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing
import com.tradingplatform.app.ui.theme.TradingNumbers
import com.tradingplatform.app.ui.theme.asNumeric
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
    onOpenSettings: () -> Unit = {},
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
            onSearchQueryChange = { viewModel.onSymbolSearchQueryChanged(it) },
            onLoadMore = { viewModel.loadMoreSymbols() },
            onAddSymbol = { viewModel.addSymbol(it) },
            onRemoveSymbol = { viewModel.removeSymbol(it) },
            onDismiss = { showSymbolPicker = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Marchés") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Réglages",
                        )
                    }
                },
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
                    // Même composant d'erreur (avec « Réessayer ») que le reste de l'app, à la place
                    // d'un texte rouge sans action.
                    ErrorBanner(
                        message = state.message,
                        onRetry = { viewModel.refresh() },
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    )
                }
            }

            is MarketDataUiState.Success -> {
                // Les cours arrivent en continu (WS / polling) ; l'indicateur ne couvre que le
                // rafraîchissement REST explicite demandé par le swipe.
                val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

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
                            // Marge basse : le FAB ne doit pas masquer la dernière carte.
                            contentPadding = PaddingValues(
                                start = Spacing.lg,
                                top = Spacing.sm,
                                end = Spacing.lg,
                                bottom = Spacing.xxxl + Spacing.xxl,
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
                                        // Suppression sans confirmation mais réversible : « Annuler »
                                        // ré-ajoute le symbole (la watchlist Room le réaffiche).
                                        scope.launch {
                                            snackbarHostState.currentSnackbarData?.dismiss()
                                            val result = snackbarHostState.showSnackbar(
                                                message = "Symbole retiré",
                                                actionLabel = "Annuler",
                                                duration = SnackbarDuration.Long,
                                            )
                                            if (result == SnackbarResult.ActionPerformed) {
                                                viewModel.addSymbol(symbol)
                                            }
                                        }
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
    modifier: Modifier = Modifier,
    onSourceTap: (String) -> Unit = {},
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

/**
 * Carte de watchlist sur 2 lignes :
 * - ligne 1 : symbole à gauche, prix à droite (tap sur le prix = source des données) ;
 * - ligne 2 : sparkline compacte à gauche, variation % à droite.
 *
 * Le détail (bid / ask, volume, variation, horodatage, source) est masqué et se déplie au tap
 * sur la carte (progressive disclosure).
 */
@Composable
private fun WatchlistCard(
    symbol: String,
    quote: Quote?,
    modifier: Modifier = Modifier,
    sparklinePoints: List<BigDecimal>? = null,
    onSourceTap: (String) -> Unit = {},
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val sparkline = sparklinePoints?.takeIf { it.size >= 2 }

    TradingCard(
        onClick = { expanded = !expanded },
        modifier = modifier.semantics {
            stateDescription = if (expanded) "Détails affichés" else "Détails masqués"
        },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            // Ligne 1 : symbole — prix
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = symbol,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics {
                        contentDescription = "Symbole : $symbol"
                    },
                )

                if (quote != null) {
                    // Toute la cellule prix est la cible du tap « source » (≥ 48 dp de haut) :
                    // le point de 6 dp seul était quasi impossible à viser au doigt.
                    val sourceTooltip = remember(quote.sourceName, quote.dataMode, quote.quality) {
                        buildSourceTooltip(quote)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clickable(onClickLabel = "Voir la source des données") {
                                onSourceTap(sourceTooltip)
                            },
                    ) {
                        SourceQualityDot(quote = quote)
                        AnimatedPriceText(
                            value = quote.price,
                            style = TradingNumbers.titleMedium,
                        )
                    }
                } else {
                    Text(
                        text = "\u2014",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "Cours en cours de chargement" },
                    )
                }
            }

            // Ligne 2 : sparkline — variation %
            if (quote != null || sparkline != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (sparkline != null) {
                        SparklineChart(
                            dataPoints = sparkline,
                            modifier = Modifier.weight(1f),
                            height = Spacing.xl,
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    if (quote != null) {
                        ChangePercentText(
                            changePercent = quote.changePercent,
                            change = quote.change,
                        )
                    }
                }
            }

            // Détail dépliable
            AnimatedVisibility(visible = expanded && quote != null) {
                if (quote != null) {
                    QuoteDetails(quote = quote)
                }
            }
        }
    }
}

/** Bloc de détail d'une carte dépliée : bid/ask, volume, variation, horodatage, source. */
@Composable
private fun QuoteDetails(
    quote: Quote,
    modifier: Modifier = Modifier,
) {
    val bid = quote.bid
    val ask = quote.ask
    val change = quote.change
    val volume = quote.volume ?: 0L
    val volumeFormatted = remember(volume) {
        NumberFormat.getNumberInstance(Locale.FRENCH).format(volume)
    }
    val formattedTimestamp = remember(quote.timestamp) {
        quote.timestamp
            .atZone(ZoneId.systemDefault())
            .let { DateTimeFormatter.ofPattern("HH:mm:ss").format(it) }
    }
    val sourceLabel = remember(quote.sourceName, quote.dataMode, quote.quality) {
        buildSourceTooltip(quote)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        if (bid != null && ask != null) {
            DetailRow(
                label = "Bid / Ask",
                value = "${bid.toPlainString()} / ${ask.toPlainString()}",
                description = "Bid : ${bid.toPlainString()} euros, Ask : ${ask.toPlainString()} euros",
            )
        }
        if (volume > 0) {
            DetailRow(
                label = "Volume",
                value = volumeFormatted,
                description = "Volume : $volumeFormatted",
            )
        }
        if (change != null) {
            val changeFormatted = formatPnlAmount(change, "€")
            DetailRow(
                label = "Variation",
                value = changeFormatted,
                description = "Variation : $changeFormatted",
                valueColor = pnlColor(change),
            )
        }
        DetailRow(
            label = "Cours de",
            value = formattedTimestamp,
            description = "Cours de $formattedTimestamp",
        )
        DetailRow(
            label = "Source",
            value = sourceLabel,
            description = "Source : $sourceLabel",
            numeric = false,
        )
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    description: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    numeric: Boolean = true,
) {
    val valueStyle = if (numeric) {
        MaterialTheme.typography.bodySmall.asNumeric()
    } else {
        MaterialTheme.typography.bodySmall
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(Spacing.sm))
        Text(
            text = value,
            style = valueStyle,
            color = valueColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
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
 * Le tap (sur la cellule prix parente) affiche un snackbar avec le détail de la source.
 */
@Composable
private fun SourceQualityDot(
    quote: Quote,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current
    val dotColor = when (quote.dataMode) {
        "realtime" -> extendedColors.dataRealtime
        "polling" -> extendedColors.dataPolling
        else -> extendedColors.dataStale
    }

    val tooltipMessage = remember(quote.sourceName, quote.dataMode, quote.quality) {
        buildSourceTooltip(quote)
    }

    // Indicateur purement visuel : le tap est porté par la cellule prix parente (cible plus grande).
    Box(
        modifier = modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(dotColor)
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

/**
 * Variation du jour en %, colorée selon le signe (`pnlPositive` / `pnlNegative`). Le signe « + »
 * explicite garde l'information lisible sans la couleur (daltonisme, TalkBack).
 */
@Composable
private fun ChangePercentText(
    changePercent: Double?,
    change: BigDecimal?,
    modifier: Modifier = Modifier,
) {
    if (changePercent == null || change == null || !changePercent.isFinite()) {
        // Pas d'historique comparable sur la journée (marché fraîchement
        // ouvert, symbole sans cours de clôture précédent) — afficher un
        // placeholder plutôt que crasher.
        Text(
            text = "\u2014",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.semantics {
                contentDescription = "Variation indisponible"
            },
        )
        return
    }

    val percentFormatted = remember(changePercent) {
        val prefix = if (changePercent > 0) "+" else ""
        "$prefix${"%.2f".format(Locale.FRENCH, changePercent)}%"
    }

    val verboseDescription = remember(changePercent, change) {
        val label = when {
            change > BigDecimal.ZERO -> "Hausse"
            change < BigDecimal.ZERO -> "Baisse"
            else -> "Variation"
        }
        "$label : $percentFormatted"
    }

    Text(
        text = percentFormatted,
        style = MaterialTheme.typography.bodyMedium.asNumeric(),
        color = pnlColor(change),
        textAlign = TextAlign.End,
        modifier = modifier.semantics {
            contentDescription = verboseDescription
        },
    )
}
