package com.tradingplatform.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Affiche l'horodatage de la dernière synchronisation du cache.
 *
 * - Si [syncedAt] == 0L : ne rien afficher.
 * - Si < 1 min depuis la sync : affiche "À jour" (Couleur neutre).
 * - Si < 5 min : affiche "Données du HH:mm" (Couleur warning/jaune).
 * - Si > 5 min : affiche "Données du HH:mm" (Couleur offline/rouge).
 *
 * Style : [MaterialTheme.typography.labelSmall].
 */
@Composable
fun CacheTimestamp(
    syncedAt: Long,
    modifier: Modifier = Modifier,
) {
    if (syncedAt == 0L) return

    val extendedColors = LocalExtendedColors.current
    val neutralColor = MaterialTheme.colorScheme.onSurfaceVariant
    val now = System.currentTimeMillis()
    val ageMs = now - syncedAt

    val (text, color) = remember(syncedAt, ageMs) {
        val ageMins = ageMs / 60_000L
        val textColor = when {
            ageMins < 1 -> neutralColor
            ageMins < 5 -> extendedColors.onWarningContainer
            else -> extendedColors.statusOffline
        }

        val label = if (ageMins < 1) {
            "À jour"
        } else {
            val instant = Instant.ofEpochMilli(syncedAt)
            val local = instant.atZone(ZoneId.systemDefault())
            val time = DateTimeFormatter.ofPattern("HH:mm").format(local)
            "Données du $time"
        }
        label to textColor
    }

    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier,
    )
}
