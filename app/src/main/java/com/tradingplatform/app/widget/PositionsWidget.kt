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
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.tradingplatform.app.MainActivity
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.db.entity.PositionEntity
import com.tradingplatform.app.di.WidgetEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Widget Positions (2x2 minimum).
 *
 * Affiche :
 * - Top 5 positions depuis Room (positions)
 * - Symbole + P&L coloré (vert/rouge selon signe)
 * - Timestamp synced_at (obligatoire — données de trading)
 * - Tap → ouvre l'app sur PositionsScreen
 *
 * IMPORTANT : lit le cache Room uniquement via DAO. Pas d'appel réseau depuis un widget.
 */
class PositionsWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ep = EntryPointAccessors
            .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)

        val dataStore = ep.encryptedDataStore()
        val positionDao = ep.positionDao()

        // Lecture depuis Room — pas d'appel réseau depuis un widget
        val portfolioId = dataStore.readString(DataStoreKeys.PORTFOLIO_ID)
        val positions = positionDao.getAll().take(5)  // Top 5

        provideContent {
            GlanceTheme {
                PositionsWidgetContent(
                    positions = positions,
                    hasPortfolio = portfolioId != null,
                )
            }
        }
    }
}

@Composable
private fun PositionsWidgetContent(
    positions: List<PositionEntity>,
    hasPortfolio: Boolean,
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        // En-tête
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Positions",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = GlanceModifier.defaultWeight(),
            )
            if (positions.isNotEmpty()) {
                val latestSync = positions.maxOf { it.syncedAt }
                Text(
                    text = "Sync ${formatSyncTime(latestSync)}",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 10.sp,
                    ),
                )
            }
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

        if (positions.isEmpty()) {
            Text(
                text = "Aucune position ouverte",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp,
                ),
            )
            return@Column
        }

        // Liste des positions (top 5)
        positions.forEach { position ->
            PositionRow(position = position)
        }
    }
}

@Composable
private fun PositionRow(position: PositionEntity) {
    val unrealizedPnl = runCatching { BigDecimal(position.unrealizedPnl) }.getOrNull()
    val isPositive = unrealizedPnl != null && unrealizedPnl > BigDecimal.ZERO
    val isNegative = unrealizedPnl != null && unrealizedPnl < BigDecimal.ZERO

    val pnlColor = when {
        isPositive -> Color(0xFF34D399)  // emerald-400
        isNegative -> Color(0xFFFB7185)  // rose-400
        else -> Color(0xFF94A3B8)        // slate-400 (neutre)
    }

    val formattedPnl = if (unrealizedPnl != null) {
        val sign = if (isPositive) "+" else ""
        "$sign${String.format(java.util.Locale.FRENCH, "%.2f", unrealizedPnl)} €"
    } else {
        "—"
    }

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Symbole
        Text(
            text = position.symbol,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            ),
            modifier = GlanceModifier.defaultWeight(),
        )
        Spacer(modifier = GlanceModifier.width(4.dp))
        // P&L
        Text(
            text = formattedPnl,
            style = TextStyle(
                color = ColorProvider(day = pnlColor, night = pnlColor),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
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
