package com.tradingplatform.app.widget

import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Couleurs de trading partagées entre tous les widgets Glance.
 *
 * Identiques aux couleurs définies dans [com.tradingplatform.app.ui.theme.ExtendedColors]
 * (pnlPositive / pnlNegative / pnlNeutral) mais sous forme de constantes, car les
 * composables Glance n'ont pas accès à MaterialTheme ni à LocalExtendedColors.
 *
 * À mettre à jour en parallèle de ExtendedColors si le design system évolue.
 */
internal object WidgetColors {
    val PnlPositive = Color(0xFF34D399)  // emerald-400 — gain / device online
    val PnlNegative = Color(0xFFFB7185)  // rose-400    — perte / device offline
    val PnlNeutral  = Color(0xFF94A3B8)  // slate-400   — neutre / pas de variation
}

/**
 * Formatte un timestamp Room en label lisible pour les widgets Glance.
 *
 * - < 1 min   → "maintenant"
 * - < 60 min  → "il y a Xmin"
 * - ≥ 60 min  → "HH:mm"
 *
 * Utilisé pour les champs [syncedAt] des entités Room (positions, pnl_snapshots, quotes, devices).
 * Pour les alertes FCM (qui ont un format différent avec les heures), voir [AlertsWidget].
 */
internal fun formatWidgetSyncTime(syncedAt: Long): String {
    val now = System.currentTimeMillis()
    val diffMin = (now - syncedAt) / 60_000
    return when {
        diffMin < 1  -> "maintenant"
        diffMin < 60 -> "il y a ${diffMin}min"
        else         -> SimpleDateFormat("HH:mm", java.util.Locale.FRENCH).format(Date(syncedAt))
    }
}
