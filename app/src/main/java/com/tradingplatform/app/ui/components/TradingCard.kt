package com.tradingplatform.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tradingplatform.app.ui.theme.LocalExtendedColors

/**
 * Carte standard de l'app.
 *
 * Remplace `Card(colors = CardDefaults.cardColors(containerColor = extendedColors.cardSurface))`,
 * qui rendait la carte quasi invisible en thème clair : blanc sur `background` slate-50 = 1,05:1,
 * élévation 0. Ici la carte porte une bordure fine (`divider`) et une élévation de 1 dp, ce qui
 * la sépare du fond en clair comme en sombre.
 *
 * @param onClick   rend la carte cliquable (ripple + rôle Button pour TalkBack) ; null = statique.
 * @param elevated  utilise `cardSurfaceElevated` (bloc imbriqué / mise en avant).
 */
@Composable
fun TradingCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    elevated: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val extendedColors = LocalExtendedColors.current
    val colors = CardDefaults.cardColors(
        containerColor = if (elevated) extendedColors.cardSurfaceElevated else extendedColors.cardSurface,
    )
    val elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    val border = BorderStroke(width = 1.dp, color = extendedColors.divider)
    val cardModifier = modifier.fillMaxWidth()

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = cardModifier,
            colors = colors,
            elevation = elevation,
            border = border,
            content = content,
        )
    } else {
        Card(
            modifier = cardModifier,
            colors = colors,
            elevation = elevation,
            border = border,
            content = content,
        )
    }
}
