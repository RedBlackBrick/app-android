package com.tradingplatform.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.tradingplatform.app.data.local.db.CacheTtl
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Niveau de fraîcheur d'une donnée en cache — pilote la couleur de [CacheTimestamp]. */
enum class CacheFreshness { NEUTRAL, WARNING, OFFLINE }

/**
 * Libellé pur de [CacheTimestamp] (testable en JVM).
 *
 * @property freshness couleur : NEUTRAL < [warnMs] ≤ WARNING < [ttlMs] ≤ OFFLINE
 * @property upToDate `true` → afficher « À jour » (âge < 1 min et niveau NEUTRAL),
 *   sinon « Données du … » avec l'heure de sync.
 */
data class CacheTimestampLabel(val freshness: CacheFreshness, val upToDate: Boolean)

private const val UP_TO_DATE_MS = 60_000L

/**
 * Calcule le niveau de fraîcheur pour une donnée âgée de [ageMs] dont le TTL est [ttlMs]
 * (`CacheTtl.*_MS` de l'entité affichée). Un âge négatif (horloge décalée) est traité comme 0.
 */
fun cacheTimestampLabel(
    ageMs: Long,
    ttlMs: Long,
    warnMs: Long = ttlMs / 2,
): CacheTimestampLabel {
    val age = ageMs.coerceAtLeast(0L)
    val freshness = when {
        age >= ttlMs -> CacheFreshness.OFFLINE
        age >= warnMs -> CacheFreshness.WARNING
        else -> CacheFreshness.NEUTRAL
    }
    return CacheTimestampLabel(
        freshness = freshness,
        upToDate = freshness == CacheFreshness.NEUTRAL && age < UP_TO_DATE_MS,
    )
}

/**
 * Heure de sync affichée : "HH:mm" le jour même, "dd/MM HH:mm" sinon (une donnée de la veille
 * n'est jamais présentée comme du jour).
 */
fun formatCacheTime(syncedAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val synced = Instant.ofEpochMilli(syncedAt).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val pattern = if (synced.toLocalDate() == today) "HH:mm" else "dd/MM HH:mm"
    return DateTimeFormatter.ofPattern(pattern).format(synced)
}

/**
 * Affiche l'horodatage de la dernière synchronisation du cache.
 *
 * - Si [syncedAt] == 0L : ne rien afficher.
 * - Âge < 1 min (et < [warnMs]) : « À jour », couleur neutre.
 * - Âge < [warnMs] : « Données du HH:mm », couleur neutre.
 * - [warnMs] ≤ âge < [ttlMs] : « Données du HH:mm », couleur warning.
 * - Âge ≥ [ttlMs] : « Données du HH:mm » (ou « dd/MM HH:mm »), couleur offline.
 *
 * Les appelants passent le TTL de l'entité affichée ([CacheTtl.POSITIONS_MS],
 * [CacheTtl.DEVICES_MS], …) ; défaut [CacheTtl.DEFAULT_UI_MS] (10 min, CLAUDE.md §2).
 *
 * Style : [MaterialTheme.typography.labelSmall].
 */
@Composable
fun CacheTimestamp(
    syncedAt: Long,
    modifier: Modifier = Modifier,
    ttlMs: Long = CacheTtl.DEFAULT_UI_MS,
    warnMs: Long = ttlMs / 2,
) {
    if (syncedAt == 0L) return

    val extendedColors = LocalExtendedColors.current
    val neutralColor = MaterialTheme.colorScheme.onSurfaceVariant
    val now = System.currentTimeMillis()
    val ageMs = now - syncedAt

    val (text, color) = remember(syncedAt, ageMs, ttlMs, warnMs) {
        val label = cacheTimestampLabel(ageMs, ttlMs, warnMs)
        val textColor = when (label.freshness) {
            CacheFreshness.NEUTRAL -> neutralColor
            CacheFreshness.WARNING -> extendedColors.onWarningContainer
            CacheFreshness.OFFLINE -> extendedColors.statusOffline
        }
        val text = if (label.upToDate) "À jour" else "Données du ${formatCacheTime(syncedAt, now)}"
        text to textColor
    }

    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier,
    )
}
