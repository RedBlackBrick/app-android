package com.tradingplatform.app.ui.screens.dashboard.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.tradingplatform.app.ui.components.TradingCard
import com.tradingplatform.app.ui.screens.dashboard.StrategiesEntryModel
import com.tradingplatform.app.ui.theme.Spacing

/**
 * Entrée « Stratégies — 3 actives » vers l'écran Stratégies ([onClick]) : une ligne discrète, cible
 * tactile ≥ 48 dp, sans donnée superflue (le détail vit dans l'écran / sur le web).
 */
@Composable
internal fun DashboardStrategiesRow(
    model: StrategiesEntryModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TradingCard(modifier = modifier, onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.xxxl)
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                // Une seule phrase TalkBack ; le nœud de la carte porte le rôle bouton et le clic.
                .clearAndSetSemantics { contentDescription = model.spokenDescription },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = model.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = model.value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
