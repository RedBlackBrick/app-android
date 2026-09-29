package com.tradingplatform.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.core.content.edit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.tradingplatform.app.MainActivity
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.db.CacheTtl
import com.tradingplatform.app.data.local.db.entity.PnlSnapshotEntity
import com.tradingplatform.app.di.WidgetEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.math.BigDecimal

/**
 * Widget P&L (2x1 minimum).
 *
 * Supports configurable period (day/week/month) per instance.
 * Period is stored in SharedPreferences keyed by appWidgetId.
 *
 * Affiche :
 * - P&L total de la période configurée depuis Room (pnl_snapshots, une ligne par période,
 *   forme `/pnl` — écrite par getPnlSummary via WidgetUpdateWorker/Dashboard)
 * - Couleur verte si positif, rouge si négatif
 * - Timestamp synced_at (obligatoire — données de trading)
 * - Tap → ouvre l'app sur DashboardScreen
 */
class PnlWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ep = EntryPointAccessors
            .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)

        val dataStore = ep.encryptedDataStore()
        val pnlDao = ep.pnlDao()

        val portfolioId = dataStore.readString(DataStoreKeys.PORTFOLIO_ID)

        // Read configured period for this widget instance
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val period = readConfiguredPeriod(context, appWidgetId)
        val pnlSnapshot = pnlDao.getByPeriod(period)

        // Timestamp de la dernière tentative de sync (non sensible — SharedPreferences plain)
        val lastSyncAttempt = WidgetUpdateWorker.readLastSyncAttempt(context)

        provideContent {
            GlanceTheme {
                PnlWidgetContent(
                    pnlSnapshot = pnlSnapshot,
                    hasPortfolio = portfolioId != null,
                    periodLabel = periodDisplayLabel(period),
                    lastSyncAttempt = lastSyncAttempt,
                )
            }
        }
    }

    companion object {
        // Plain SharedPreferences is intentional here. This stores only display
        // period preferences ("day", "week", "month") — non-sensitive UI config.
        // EncryptedSharedPreferences would add overhead and KeyStore fragility for
        // no security benefit. Sensitive data (tokens, portfolio IDs) is stored in
        // EncryptedDataStore, never here.
        const val PREFS_NAME = "pnl_widget_prefs"
        const val DEFAULT_PERIOD = "day"
        private const val PERIOD_KEY_PREFIX = "period_"

        val AVAILABLE_PERIODS = listOf("day", "week", "month")

        fun readConfiguredPeriod(context: Context, appWidgetId: Int): String {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString("$PERIOD_KEY_PREFIX$appWidgetId", DEFAULT_PERIOD) ?: DEFAULT_PERIOD
        }

        fun saveConfiguredPeriod(context: Context, appWidgetId: Int, period: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit { putString("$PERIOD_KEY_PREFIX$appWidgetId", period) }
        }

        /** Nettoyage à la suppression d'une instance ([PnlWidgetReceiver.onDeleted]). */
        fun clearConfiguredPeriod(context: Context, appWidgetId: Int) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit { remove("$PERIOD_KEY_PREFIX$appWidgetId") }
        }

        /**
         * Toutes les périodes configurées par une instance de widget (clés `period_<appWidgetId>`),
         * pour que [WidgetUpdateWorker] synchronise chacune d'elles. Les valeurs inconnues
         * (hors [AVAILABLE_PERIODS]) sont ignorées. Les clés d'un widget supprimé sont retirées
         * par [PnlWidgetReceiver.onDeleted] ([clearConfiguredPeriod]).
         */
        fun configuredPeriods(context: Context): Set<String> =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all
                .filterKeys { it.startsWith(PERIOD_KEY_PREFIX) }
                .values
                .filterIsInstance<String>()
                .filter { it in AVAILABLE_PERIODS }
                .toSet()

        fun periodDisplayLabel(period: String): String = when (period) {
            "day" -> "Jour"
            "week" -> "Semaine"
            "month" -> "Mois"
            else -> "Jour"
        }
    }
}

@Composable
private fun PnlWidgetContent(
    pnlSnapshot: PnlSnapshotEntity?,
    hasPortfolio: Boolean,
    periodLabel: String,
    lastSyncAttempt: Long,
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Header with period label
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "P&L $periodLabel",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }

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
            val waitingLabel = if (lastSyncAttempt > 0L) {
                "Tentative ${formatWidgetSyncTime(lastSyncAttempt, ttlMs = Long.MAX_VALUE).text}"
            } else {
                "En attente de sync"
            }
            Text(
                text = waitingLabel,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 11.sp,
                ),
            )
            return@Column
        }

        val totalReturn = runCatching { BigDecimal(pnlSnapshot.totalPnl) }.getOrNull()
        val isPositive = totalReturn != null && totalReturn > BigDecimal.ZERO
        val isNegative = totalReturn != null && totalReturn < BigDecimal.ZERO

        val pnlColor = when {
            isPositive -> WidgetColors.Positive
            isNegative -> WidgetColors.Negative
            else -> WidgetColors.Neutral
        }

        val formattedReturn = if (totalReturn != null) {
            val sign = if (isPositive) "+" else ""
            "$sign${String.format(java.util.Locale.FRENCH, "%.2f", totalReturn)} €"
        } else {
            "—"
        }

        Text(
            text = formattedReturn,
            style = TextStyle(
                color = pnlColor.provider(),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            ),
        )

        // total_pnl_percent est stocké en fraction (mapper /pnl) → ×100 pour l'affichage
        val totalReturnPct = pnlSnapshot.totalPnlPercent
        val pctSign = if (totalReturnPct > 0) "+" else ""
        val pctText = "$pctSign${String.format(java.util.Locale.FRENCH, "%.2f", totalReturnPct * 100)}%"
        Text(
            text = pctText,
            style = TextStyle(
                color = pnlColor.provider(),
                fontSize = 12.sp,
            ),
        )

        // Timestamp synced_at — obligatoire ; badge « périmé » au-delà de maxOf(PNL_MS, WIDGET_STALE_GRACE_MS)
        val syncLabel = formatWidgetSyncTime(pnlSnapshot.syncedAt, widgetStaleThreshold(CacheTtl.PNL_MS))
        Text(
            text = syncLabel.withSyncPrefix(),
            style = TextStyle(
                color = syncLabelColor(syncLabel),
                fontSize = 11.sp,
            ),
        )
    }
}

