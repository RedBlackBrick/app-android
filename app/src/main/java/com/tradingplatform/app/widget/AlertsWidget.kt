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
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.tradingplatform.app.MainActivity
import com.tradingplatform.app.data.local.db.entity.AlertEntity
import com.tradingplatform.app.di.WidgetEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Widget Alertes (2x1 minimum).
 *
 * Affiche :
 * - Nombre d'alertes non lues depuis Room (alerts)
 * - Titre de la dernière alerte
 * - Timestamp de la dernière alerte reçue
 * - Tap → ouvre l'app sur AlertListScreen
 *
 * Les alertes viennent de FCM → Room uniquement (pas d'appel réseau).
 * Ce widget fonctionne entièrement offline.
 */
class AlertsWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ep = EntryPointAccessors
            .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)

        val alertDao = ep.alertDao()

        // Lecture depuis Room — fonctionne entièrement offline (FCM → Room)
        val allAlerts = alertDao.getAll()
        val unreadCount = allAlerts.count { !it.read }
        val latestAlert = allAlerts.firstOrNull()  // déjà trié par received_at DESC

        // Timestamp de la dernière tentative de sync Worker (pour la purge des alertes)
        // Les alertes elles-mêmes viennent de FCM — le Worker effectue seulement la purge.
        val lastSyncAttempt = WidgetUpdateWorker.readLastSyncAttempt(context)

        provideContent {
            GlanceTheme {
                AlertsWidgetContent(
                    unreadCount = unreadCount,
                    latestAlert = latestAlert,
                    lastSyncAttempt = lastSyncAttempt,
                )
            }
        }
    }
}

@Composable
private fun AlertsWidgetContent(
    unreadCount: Int,
    latestAlert: AlertEntity?,
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
        // En-tête avec compteur non lus et timestamp de dernière activité
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Alertes",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = GlanceModifier.defaultWeight(),
            )
            if (unreadCount > 0) {
                Text(
                    text = "$unreadCount non lue${if (unreadCount > 1) "s" else ""}",
                    style = TextStyle(
                        color = GlanceTheme.colors.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            } else if (lastSyncAttempt > 0L) {
                // Afficher le timestamp de la dernière purge/sync Worker
                Text(
                    text = "Sync ${formatAlertTime(lastSyncAttempt)}",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 10.sp,
                    ),
                )
            }
        }

        if (latestAlert == null) {
            Text(
                text = "Aucune alerte",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp,
                ),
            )
            return@Column
        }

        // Titre de la dernière alerte
        Text(
            text = latestAlert.title,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 13.sp,
                fontWeight = if (!latestAlert.read) FontWeight.Bold else FontWeight.Normal,
            ),
            maxLines = 1,
        )

        // Timestamp de la dernière alerte
        Text(
            text = formatAlertTime(latestAlert.receivedAt),
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 10.sp,
            ),
        )
    }
}

private fun formatAlertTime(receivedAt: Long): String {
    val now = System.currentTimeMillis()
    val diffMin = (now - receivedAt) / 60_000
    return when {
        diffMin < 1 -> "maintenant"
        diffMin < 60 -> "il y a ${diffMin}min"
        diffMin < 60 * 24 -> "il y a ${diffMin / 60}h"
        else -> SimpleDateFormat("dd/MM HH:mm", java.util.Locale.FRENCH).format(Date(receivedAt))
    }
}
