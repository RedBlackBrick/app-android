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
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.tradingplatform.app.MainActivity
import com.tradingplatform.app.data.local.db.entity.QuoteEntity
import com.tradingplatform.app.di.WidgetEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.math.BigDecimal

/**
 * Widget Quote (1x1 minimum).
 *
 * Affiche :
 * - Symbole configurable (lu depuis SharedPreferences keyed par appWidgetId)
 * - Prix du cours depuis Room (quotes)
 * - Variation sur la période (change_percent)
 * - Timestamp synced_at (obligatoire — données de trading)
 * - Tap → ouvre l'app
 *
 * Le symbole est configuré via [QuoteWidgetConfigureActivity] lors de l'ajout du widget.
 * Chaque instance peut afficher un symbole différent.
 *
 * IMPORTANT : lit le cache Room uniquement via DAO. Pas d'appel réseau depuis un widget.
 */
class QuoteWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ep = EntryPointAccessors
            .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)

        val quoteDao = ep.quoteDao()

        // Lire le symbole configuré pour cette instance (SharedPreferences keyed par appWidgetId)
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val symbol = readConfiguredSymbol(context, appWidgetId) ?: DEFAULT_SYMBOL

        // Lecture depuis Room — pas d'appel réseau depuis un widget
        val quote = quoteDao.getBySymbol(symbol)

        // Timestamp de la dernière tentative de sync (non sensible — SharedPreferences plain)
        val lastSyncAttempt = WidgetUpdateWorker.readLastSyncAttempt(context)

        provideContent {
            GlanceTheme {
                QuoteWidgetContent(
                    symbol = symbol,
                    quote = quote,
                    lastSyncAttempt = lastSyncAttempt,
                )
            }
        }
    }

    companion object {
        const val DEFAULT_SYMBOL = "AAPL"

        // Plain SharedPreferences is intentional here. This stores only public ticker
        // symbols (e.g. "AAPL", "TSLA") — non-sensitive, publicly available market
        // identifiers. EncryptedSharedPreferences would add overhead and KeyStore
        // fragility for no security benefit. Sensitive data (tokens, portfolio IDs)
        // is stored in EncryptedDataStore, never here.
        const val PREFS_NAME = "quote_widget_prefs"

        fun readConfiguredSymbol(context: Context, appWidgetId: Int): String? {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString("ticker_$appWidgetId", null)
        }

        fun saveConfiguredSymbol(context: Context, appWidgetId: Int, symbol: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit { putString("ticker_$appWidgetId", symbol) }
        }

        fun clearConfiguredSymbol(context: Context, appWidgetId: Int) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit { remove("ticker_$appWidgetId") }
        }
    }
}

@Composable
private fun QuoteWidgetContent(
    symbol: String,
    quote: QuoteEntity?,
    lastSyncAttempt: Long,
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .padding(8.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Symbole
        Text(
            text = symbol,
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            ),
        )

        if (quote == null) {
            Text(
                text = "—",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            val waitingLabel = if (lastSyncAttempt > 0L) {
                "Tentative ${formatWidgetSyncTime(lastSyncAttempt)}"
            } else {
                "En attente"
            }
            Text(
                text = waitingLabel,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 9.sp,
                ),
            )
            return@Column
        }

        val price = runCatching { BigDecimal(quote.price) }.getOrNull()
        val isPositive = quote.changePercent > 0
        val isNegative = quote.changePercent < 0

        val changeColor = when {
            isPositive -> WidgetColors.PnlPositive
            isNegative -> WidgetColors.PnlNegative
            else       -> WidgetColors.PnlNeutral
        }

        // Prix
        val formattedPrice = if (price != null) {
            String.format(java.util.Locale.FRENCH, "%.2f", price)
        } else {
            "—"
        }
        Text(
            text = formattedPrice,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            ),
        )

        // Variation %
        val pctSign = if (isPositive) "+" else ""
        val pctText = "$pctSign${String.format(java.util.Locale.FRENCH, "%.2f", quote.changePercent)}%"
        Text(
            text = pctText,
            style = TextStyle(
                color = ColorProvider(day = changeColor, night = changeColor),
                fontSize = 11.sp,
            ),
        )

        // Timestamp synced_at — obligatoire (CLAUDE.md §2)
        Text(
            text = formatWidgetSyncTime(quote.syncedAt),
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 9.sp,
            ),
        )
    }
}

