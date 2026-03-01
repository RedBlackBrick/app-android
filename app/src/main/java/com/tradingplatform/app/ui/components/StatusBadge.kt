package com.tradingplatform.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing

/**
 * Badge de statut en forme de pilule (pill shape).
 *
 * Accessible via TalkBack : contentDescription = "Statut : [text]".
 *
 * Usage direct :
 * ```kotlin
 * StatusBadge(text = "En ligne", color = LocalExtendedColors.current.statusOnline)
 * ```
 *
 * Ou via les variantes prédéfinies :
 * ```kotlin
 * OnlineBadge()
 * OfflineBadge()
 * OpenPositionBadge()
 * ClosedPositionBadge()
 * ```
 */
@Composable
fun StatusBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(
                color = color.copy(alpha = 0.15f),
                shape = RoundedCornerShape(50),
            )
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
            .semantics { contentDescription = "Statut : $text" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

// ── Variantes prédéfinies ─────────────────────────────────────────────────────

/**
 * Badge "En ligne" — couleur success (vert emerald).
 */
@Composable
fun OnlineBadge(modifier: Modifier = Modifier) {
    StatusBadge(
        text = "En ligne",
        color = LocalExtendedColors.current.statusOnline,
        modifier = modifier,
    )
}

/**
 * Badge "Hors ligne" — couleur error (rouge rose).
 */
@Composable
fun OfflineBadge(modifier: Modifier = Modifier) {
    StatusBadge(
        text = "Hors ligne",
        color = LocalExtendedColors.current.statusOffline,
        modifier = modifier,
    )
}

/**
 * Badge "Ouverte" pour une position — couleur primary (indigo).
 */
@Composable
fun OpenPositionBadge(modifier: Modifier = Modifier) {
    StatusBadge(
        text = "Ouverte",
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}

/**
 * Badge "Fermée" pour une position — couleur neutre avec bordure outline.
 */
@Composable
fun ClosedPositionBadge(modifier: Modifier = Modifier) {
    val outlineColor = MaterialTheme.colorScheme.outline
    Box(
        modifier = modifier
            .background(
                color = Color.Transparent,
                shape = RoundedCornerShape(50),
            )
            .border(
                width = 1.dp,
                color = outlineColor,
                shape = RoundedCornerShape(50),
            )
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
            .semantics { contentDescription = "Statut : Fermée" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Fermée",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
