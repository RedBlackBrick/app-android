package com.tradingplatform.app.ui.screens.alerts

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.tradingplatform.app.domain.model.AlertType
import com.tradingplatform.app.ui.theme.Spacing

/**
 * Horizontally scrollable row of [FilterChip]s for alert type multi-select filtering.
 *
 * @param selectedTypes Currently active filter types (empty = no filter active).
 * @param onToggleType Called when the user taps a chip — the caller should toggle the type
 *                     in the selected set.
 * @param modifier Standard Compose modifier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertFilterBar(
    selectedTypes: Set<AlertType>,
    onToggleType: (AlertType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        AlertType.entries.forEach { type ->
            val label = alertTypeFilterLabel(type)
            val isSelected = type in selectedTypes
            val a11yDescription = if (isSelected) {
                "Filtre $label actif, appuyez pour retirer"
            } else {
                "Filtre $label inactif, appuyez pour activer"
            }

            FilterChip(
                selected = isSelected,
                onClick = { onToggleType(type) },
                label = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                modifier = Modifier.semantics {
                    contentDescription = a11yDescription
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            )
        }
    }
}

/**
 * French labels for filter chips — shorter than the verbose [alertTypeLabel] used in
 * accessibility descriptions.
 */
internal fun alertTypeFilterLabel(type: AlertType): String = when (type) {
    AlertType.PRICE_ALERT -> "Prix"
    AlertType.TRADE_EXECUTED -> "Trade"
    AlertType.DEVICE_OFFLINE -> "Device off"
    AlertType.DEVICE_ONLINE -> "Device on"
    AlertType.DEVICE_UNPAIRED -> "Désappairé"
    AlertType.SCRAPING_ERROR -> "Scraping"
    AlertType.OTA_COMPLETE -> "OTA"
    AlertType.SYSTEM_ERROR -> "Système"
    AlertType.PORTFOLIO_UPDATE -> "Portfolio"
    AlertType.UNKNOWN -> "Autre"
}
