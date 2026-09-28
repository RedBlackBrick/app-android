package com.tradingplatform.app.ui.screens.market

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tradingplatform.app.domain.model.SymbolInfo
import com.tradingplatform.app.ui.components.rememberHapticFeedback
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SymbolPickerSheet(
    symbolPickerState: SymbolPickerUiState,
    watchlistSymbols: List<String>,
    onRefresh: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onLoadMore: () -> Unit,
    onAddSymbol: (String) -> Unit,
    onRemoveSymbol: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }
    val haptic = rememberHapticFeedback()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.lg),
        ) {
            // Title
            Text(
                text = "Ajouter un symbole",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(
                    horizontal = Spacing.lg,
                    vertical = Spacing.sm,
                ),
            )

            // Search field — server-side search (debounced in the ViewModel)
            TextField(
                value = searchQuery,
                onValueChange = { input ->
                    searchQuery = input
                    onSearchQueryChange(input)
                },
                placeholder = {
                    Text(
                        text = "Rechercher un symbole…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg),
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = Spacing.sm),
                color = LocalExtendedColors.current.divider,
            )

            // Content
            when (symbolPickerState) {
                is SymbolPickerUiState.Idle -> {
                    // Shouldn't normally be seen since we refreshSymbols before showing
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.xl),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is SymbolPickerUiState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.xl),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is SymbolPickerUiState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.xl),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Text(
                            text = symbolPickerState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = "Réessayer",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .wrapContentHeight(Alignment.CenterVertically)
                                .clickable { onRefresh() },
                        )
                    }
                }

                is SymbolPickerUiState.Success -> {
                    val watchlistSet = remember(watchlistSymbols) {
                        watchlistSymbols.map { it.uppercase() }.toSet()
                    }

                    if (symbolPickerState.symbols.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.xl),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Aucun symbole trouvé",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items(
                                items = symbolPickerState.symbols,
                                key = { it.ticker },
                            ) { symbol ->
                                val isInWatchlist = symbol.ticker.uppercase() in watchlistSet
                                SymbolPickerItem(
                                    symbol = symbol,
                                    isInWatchlist = isInWatchlist,
                                    onToggle = {
                                        if (isInWatchlist) {
                                            haptic.reject()
                                            onRemoveSymbol(symbol.ticker)
                                        } else {
                                            haptic.confirm()
                                            onAddSymbol(symbol.ticker)
                                        }
                                    },
                                )
                            }

                            if (symbolPickerState.hasMore) {
                                item(key = "load_more") {
                                    LoadMoreRow(
                                        isLoading = symbolPickerState.isLoadingMore,
                                        onClick = onLoadMore,
                                    )
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
private fun LoadMoreRow(
    isLoading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        } else {
            TextButton(onClick = onClick) {
                Text("Charger plus")
            }
        }
    }
}

@Composable
private fun SymbolPickerItem(
    symbol: SymbolInfo,
    isInWatchlist: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = symbol.ticker,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = symbol.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        symbol.exchange?.let { exchange ->
            SuggestionChip(
                onClick = onToggle,
                label = { Text(exchange, style = MaterialTheme.typography.labelSmall) },
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.padding(horizontal = Spacing.xs),
            )
        }

        IconButton(
            onClick = onToggle,
            modifier = Modifier.semantics {
                contentDescription = if (isInWatchlist) {
                    "Retirer ${symbol.ticker} de la watchlist"
                } else {
                    "Ajouter ${symbol.ticker} à la watchlist"
                }
            },
        ) {
            if (isInWatchlist) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = extendedColors.success,
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
