package com.tradingplatform.app.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.glance.GlanceTheme
import androidx.glance.color.ColorProvider
import com.tradingplatform.app.data.local.db.CacheTtl
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Couleurs de trading partagées entre tous les widgets Glance.
 *
 * Identiques aux couleurs définies dans [com.tradingplatform.app.ui.theme.ExtendedColors]
 * (pnlPositive / pnlNegative / pnlNeutral / warning) mais sous forme de constantes, car les
 * composables Glance n'ont pas accès à MaterialTheme ni à LocalExtendedColors.
 *
 * À mettre à jour en parallèle de ExtendedColors si le design system évolue.
 */
internal object WidgetColors {
    val PnlPositive = Color(0xFF34D399)  // emerald-400 — gain / device online
    val PnlNegative = Color(0xFFFB7185)  // rose-400    — perte / device offline
    val PnlNeutral  = Color(0xFF94A3B8)  // slate-400   — neutre / pas de variation
    val StaleDay    = Color(0xFFD97706)  // amber-600   — donnée périmée (thème clair, = warning light)
    val StaleNight  = Color(0xFFFBBF24)  // amber-400   — donnée périmée (thème sombre, = warning dark)
}

/**
 * Libellé d'horodatage d'un widget : [text] prêt à afficher, [isStale] si la donnée a dépassé
 * son TTL (`CacheTtl.*`) — le widget colore alors le libellé ([syncLabelColor]).
 */
internal data class SyncLabel(val text: String, val isStale: Boolean)

internal const val STALE_PREFIX = "périmé · "

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH)
private val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm", Locale.FRENCH)

/**
 * Formatte un timestamp Room (`synced_at`) en libellé pour les widgets Glance.
 *
 * - < 1 min              → "maintenant"
 * - < 60 min             → "il y a Nmin"
 * - même jour calendaire → "HH:mm"
 * - sinon                → "dd/MM HH:mm" (une donnée de la veille n'est jamais affichée comme du jour)
 *
 * Au-delà de [ttlMs] (âge strictement supérieur), le libellé est préfixé par [STALE_PREFIX]
 * et [SyncLabel.isStale] vaut `true`. Un horodatage dans le futur (horloge décalée) est
 * affiché "maintenant".
 *
 * Utilisé pour les champs `syncedAt` des entités Room (positions, pnl_snapshots, quotes,
 * devices) avec le TTL de l'entité (`CacheTtl.*_MS`). Pour un simple horodatage de tentative
 * de sync, passer `ttlMs = Long.MAX_VALUE` (jamais périmé).
 * Pour les alertes FCM (format différent), voir [AlertsWidget].
 */
internal fun formatWidgetSyncTime(
    syncedAt: Long,
    ttlMs: Long,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): SyncLabel {
    val ageMs = (now - syncedAt).coerceAtLeast(0L)
    val diffMin = ageMs / 60_000L
    val base = when {
        diffMin < 1 -> "maintenant"
        diffMin < 60 -> "il y a ${diffMin}min"
        else -> {
            val synced = Instant.ofEpochMilli(syncedAt).atZone(zone)
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            if (synced.toLocalDate() == today) TIME_FORMAT.format(synced) else DATE_TIME_FORMAT.format(synced)
        }
    }
    val isStale = ageMs > ttlMs
    return SyncLabel(text = if (isStale) "$STALE_PREFIX$base" else base, isStale = isStale)
}

/**
 * Seuil « périmé » d'un widget pour une entité de TTL [entityTtlMs] :
 * `maxOf(entityTtlMs, CacheTtl.WIDGET_STALE_GRACE_MS)` — le Worker ne tourne que toutes les
 * 15 min, un TTL de 5 min rendrait le badge quasi permanent.
 */
internal fun widgetStaleThreshold(entityTtlMs: Long): Long =
    maxOf(entityTtlMs, CacheTtl.WIDGET_STALE_GRACE_MS)

/** Libellé « Sync … » d'un widget : le préfixe « Sync » est omis quand la donnée est périmée. */
internal fun SyncLabel.withSyncPrefix(): String = if (isStale) text else "Sync $text"

/** Couleur du libellé d'horodatage : ambre si périmé, sinon la couleur secondaire du thème. */
@Composable
internal fun syncLabelColor(label: SyncLabel): androidx.glance.unit.ColorProvider =
    if (label.isStale) {
        ColorProvider(day = WidgetColors.StaleDay, night = WidgetColors.StaleNight)
    } else {
        GlanceTheme.colors.onSurfaceVariant
    }
