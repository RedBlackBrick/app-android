package com.tradingplatform.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
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
import com.tradingplatform.app.data.local.db.CacheTtl
import com.tradingplatform.app.data.local.db.entity.PositionEntity
import com.tradingplatform.app.di.WidgetEntryPoint
import com.tradingplatform.app.domain.model.PositionStatus
import dagger.hilt.android.EntryPointAccessors
import java.math.BigDecimal

/**
 * Widget Positions (2x2 minimum).
 *
 * Affiche :
 * - Top 5 positions ouvertes depuis Room (positions), par exposition absolue ([topPositionsByExposure])
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
        val positions = topPositionsByExposure(positionDao.getAll())

        // Timestamp de la dernière tentative de sync (non sensible — SharedPreferences plain)
        val lastSyncAttempt = WidgetUpdateWorker.readLastSyncAttempt(context)

        provideContent {
            GlanceTheme {
                PositionsWidgetContent(
                    positions = positions,
                    hasPortfolio = portfolioId != null,
                    lastSyncAttempt = lastSyncAttempt,
                )
            }
        }
    }

    companion object {
        const val TOP_N = 5
    }
}

/**
 * « Top 5 » du widget : positions ouvertes triées par exposition absolue décroissante,
 * |quantity × (currentPrice ?: avgPrice)|. La table `positions` peut contenir des positions
 * fermées (écrites par les filtres « Fermées »/« Toutes » ou par le détail d'une position) :
 * elles sont exclues. Une ligne dont les montants ne se parsent pas n'est pas écartée mais
 * classée en dernier (exposition 0) — `runCatching` par ligne, jamais d'exception.
 */
internal fun topPositionsByExposure(
    positions: List<PositionEntity>,
    limit: Int = PositionsWidget.TOP_N,
): List<PositionEntity> =
    positions
        .filterNot { it.status.equals(PositionStatus.CLOSED.toApiString(), ignoreCase = true) }
        .map { it to exposureOf(it) }
        .sortedWith(compareByDescending<Pair<PositionEntity, BigDecimal>> { it.second }.thenBy { it.first.symbol })
        .take(limit)
        .map { it.first }

private fun exposureOf(position: PositionEntity): BigDecimal = runCatching {
    val price = position.currentPrice?.let { BigDecimal(it) } ?: BigDecimal(position.avgPrice)
    BigDecimal(position.quantity).multiply(price).abs()
}.getOrDefault(BigDecimal.ZERO)

@Composable
private fun PositionsWidgetContent(
    positions: List<PositionEntity>,
    hasPortfolio: Boolean,
    lastSyncAttempt: Long,
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
            // Timestamp synced_at — obligatoire ; « périmé » au-delà de maxOf(POSITIONS_MS, WIDGET_STALE_GRACE_MS)
            val syncLabel = if (positions.isNotEmpty()) {
                val label = formatWidgetSyncTime(positions.maxOf { it.syncedAt }, widgetStaleThreshold(CacheTtl.POSITIONS_MS))
                label.copy(text = label.withSyncPrefix())
            } else if (lastSyncAttempt > 0L) {
                val label = formatWidgetSyncTime(lastSyncAttempt, ttlMs = Long.MAX_VALUE)
                label.copy(text = "Tentative ${label.text}")
            } else {
                null
            }
            if (syncLabel != null) {
                Text(
                    text = syncLabel.text,
                    style = TextStyle(
                        color = syncLabelColor(syncLabel),
                        fontSize = 11.sp,
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
        isPositive -> WidgetColors.Positive
        isNegative -> WidgetColors.Negative
        else       -> WidgetColors.Neutral
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
                color = pnlColor.provider(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

