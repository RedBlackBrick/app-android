package com.tradingplatform.app.ui.screens.market

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Text
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
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SymbolPickerSheet(
    symbolPickerState: SymbolPickerUiState,
    watchlistSymbols: List<String>,
    onRefresh: () -> Unit,
    onAddSymbol: (String) -> Unit,
    onRemoveSymbol: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }

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

            // Search field
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        text = "Rechercher un symbole\u2026",
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
                            text = "R\u00e9essayer",
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
                        watchlistSymbols.toSet()
                    }
                    val filteredSymbols = remember(symbolPickerState.symbols, searchQuery) {
                        if (searchQuery.isBlank()) {
                            symbolPickerState.symbols
                        } else {
                            symbolPickerState.symbols.filter { symbol ->
                                symbol.contains(searchQuery.trim(), ignoreCase = true)
                            }
                        }
                    }

                    if (filteredSymbols.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.xl),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Aucun symbole trouv\u00e9",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items(
                                items = filteredSymbols,
                                key = { it },
                            ) { symbol ->
                                val isInWatchlist = symbol.uppercase() in watchlistSet
                                SymbolPickerItem(
                                    symbol = symbol,
                                    isInWatchlist = isInWatchlist,
                                    onToggle = {
                                        if (isInWatchlist) {
                                            onRemoveSymbol(symbol)
                                        } else {
                                            onAddSymbol(symbol)
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

@Composable
private fun SymbolPickerItem(
    symbol: String,
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
        Text(
            text = symbol,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        IconButton(
            onClick = onToggle,
            modifier = Modifier.semantics {
                contentDescription = if (isInWatchlist) {
                    "Retirer $symbol de la watchlist"
                } else {
                    "Ajouter $symbol \u00e0 la watchlist"
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
