package com.tradingplatform.app.ui.screens.dashboard.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.tradingplatform.app.ui.components.TradingCard
import com.tradingplatform.app.ui.screens.dashboard.RiskTileKind
import com.tradingplatform.app.ui.screens.dashboard.RiskTileModel
import com.tradingplatform.app.ui.theme.IconSize
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing

/**
 * Tuile risque (circuit-breaker), affichée UNIQUEMENT quand [RiskTileModel] existe — c'est-à-dire
 * circuit ouvert ou statut indisponible (cf. `riskTileModel`). Le signal ne repose pas sur la
 * seule couleur : une icône d'avertissement accompagne le texte.
 */
@Composable
internal fun DashboardRiskTile(
    model: RiskTileModel,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current
    // Texte : pnlNegative (≥ 4,5:1) pour le circuit ouvert, onWarningContainer pour l'indisponible
    // (le `warning` brut ne passe pas 4,5:1 sur la carte claire — il ne sert qu'à l'icône).
    val (iconTint, titleColor) = when (model.kind) {
        RiskTileKind.TRADING_SUSPENDED -> extendedColors.pnlNegative to extendedColors.pnlNegative
        RiskTileKind.STATUS_UNAVAILABLE -> extendedColors.warning to extendedColors.onWarningContainer
    }

    TradingCard(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md)
                .clearAndSetSemantics { contentDescription = model.spokenDescription },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(IconSize.md),
            )
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(
                    text = model.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = model.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = titleColor,
                )
            }
        }
    }
}
