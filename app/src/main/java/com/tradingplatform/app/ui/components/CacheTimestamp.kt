package com.tradingplatform.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Affiche l'horodatage de la dernière synchronisation du cache.
 *
 * - Si [syncedAt] == 0L : ne rien afficher.
 * - Si < 1 min depuis la sync : affiche "À jour".
 * - Sinon : affiche "Données du HH:mm".
 *
 * Couleur : [MaterialTheme.colorScheme.onSurfaceVariant] (subtile).
 * Style : [MaterialTheme.typography.labelSmall].
 *
 * Usage :
 * ```kotlin
 * CacheTimestamp(syncedAt = position.syncedAt)
 * ```
 */
@Composable
fun CacheTimestamp(
    syncedAt: Long,
    modifier: Modifier = Modifier,
) {
    if (syncedAt == 0L) return

    val now = remember { System.currentTimeMillis() }
    val ageMs = now - syncedAt

    val text = if (ageMs < 60_000L) {
        "À jour"
    } else {
        val time = remember(syncedAt) {
            val instant = Instant.ofEpochMilli(syncedAt)
            val local = instant.atZone(ZoneId.systemDefault())
            DateTimeFormatter.ofPattern("HH:mm").format(local)
        }
        "Données du $time"
    }

    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
