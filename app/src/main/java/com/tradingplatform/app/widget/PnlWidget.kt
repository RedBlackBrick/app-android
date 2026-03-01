package com.tradingplatform.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.tradingplatform.app.MainActivity
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.db.entity.PnlSnapshotEntity
import com.tradingplatform.app.di.WidgetEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Widget P&L (2x1 minimum).
 *
 * Affiche :
 * - P&L total du jour depuis Room (pnl_snapshots, période "day")
 * - Couleur verte si positif, rouge si négatif
 * - Timestamp synced_at (obligatoire — données de trading)
 * - Tap → ouvre l'app sur DashboardScreen
 *
 * IMPORTANT : lit le cache Room uniquement via DAO. Pas d'appel réseau depuis un widget.
 * Le WidgetUpdateWorker met à jour Room toutes les 15 min minimum.
 */
class PnlWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ep = EntryPointAccessors
            .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)

        val dataStore = ep.encryptedDataStore()
        val pnlDao = ep.pnlDao()

        // Lecture depuis Room — pas d'appel réseau depuis un widget
        val portfolioId = dataStore.readString(DataStoreKeys.PORTFOLIO_ID)
        val pnlSnapshot = pnlDao.getLatestByPeriod("day")

        provideContent {
            GlanceTheme {
                PnlWidgetContent(
                    pnlSnapshot = pnlSnapshot,
                    hasPortfolio = portfolioId != null,
                )
            }
        }
    }
}

@Composable
private fun PnlWidgetContent(
    pnlSnapshot: PnlSnapshotEntity?,
    hasPortfolio: Boolean,
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Titre du widget
        Text(
            text = "P&L Jour",
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            ),
        )

        if (!hasPortfolio) {
            Text(
                text = "Session expirée — ouvrez l'app",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp,
                ),
            )
            return@Column
        }

        if (pnlSnapshot == null) {
            Text(
                text = "—",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                text = "En attente de sync",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 10.sp,
                ),
            )
            return@Column
        }

        val totalPnl = runCatching { BigDecimal(pnlSnapshot.totalPnl) }.getOrNull()
        val isPositive = totalPnl != null && totalPnl > BigDecimal.ZERO
        val isNegative = totalPnl != null && totalPnl < BigDecimal.ZERO

        // Couleurs P&L — Emerald/Rose (cohérent avec le design system)
        val pnlColor = when {
            isPositive -> Color(0xFF34D399)  // emerald-400
            isNegative -> Color(0xFFFB7185)  // rose-400
            else -> Color(0xFF94A3B8)        // slate-400 (neutre)
        }

        val formattedPnl = if (totalPnl != null) {
            val sign = if (isPositive) "+" else ""
            "$sign${String.format(java.util.Locale.FRENCH, "%.2f", totalPnl)} €"
        } else {
            "—"
        }

        // Valeur P&L principale
        Text(
            text = formattedPnl,
            style = TextStyle(
                color = ColorProvider(day = pnlColor, night = pnlColor),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            ),
        )

        // Pourcentage
        val pctSign = if (pnlSnapshot.totalPnlPercent > 0) "+" else ""
        val pctText = "$pctSign${String.format(java.util.Locale.FRENCH, "%.2f", pnlSnapshot.totalPnlPercent)}%"
        Text(
            text = pctText,
            style = TextStyle(
                color = ColorProvider(day = pnlColor, night = pnlColor),
                fontSize = 12.sp,
            ),
        )

        // Timestamp synced_at — obligatoire pour toutes les données de trading (CLAUDE.md §2)
        Text(
            text = "Sync ${formatSyncTime(pnlSnapshot.syncedAt)}",
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 10.sp,
            ),
        )
    }
}

private fun formatSyncTime(syncedAt: Long): String {
    val now = System.currentTimeMillis()
    val diffMin = (now - syncedAt) / 60_000
    return when {
        diffMin < 1 -> "maintenant"
        diffMin < 60 -> "il y a ${diffMin}min"
        else -> SimpleDateFormat("HH:mm", java.util.Locale.FRENCH).format(Date(syncedAt))
    }
}
