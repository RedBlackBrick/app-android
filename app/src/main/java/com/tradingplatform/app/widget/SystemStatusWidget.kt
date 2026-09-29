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
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.tradingplatform.app.MainActivity
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.SecureReadResult
import com.tradingplatform.app.data.local.db.CacheTtl
import com.tradingplatform.app.data.local.db.entity.DeviceEntity
import com.tradingplatform.app.domain.model.DeviceStatus
import com.tradingplatform.app.di.WidgetEntryPoint
import dagger.hilt.android.EntryPointAccessors

/**
 * Widget System Status (2x1 minimum) — admin uniquement.
 *
 * Affiche :
 * - Si !is_admin (ou clé absente) : message "Réservé aux administrateurs"
 * - Si EncryptedDataStore corrompu : "Session expirée — ouvrez l'app" (comme les autres widgets)
 * - Si admin : état des devices depuis Room (devices)
 *   - Nombre de devices online/offline
 *   - Timestamp synced_at
 * - Tap → ouvre l'app sur DevicesScreen (si admin)
 *
 * Ce widget est désactivé via PackageManager pour les non-admins (CLAUDE.md §2).
 * La vérification is_admin ici est une sécurité supplémentaire (défense en profondeur).
 */
class SystemStatusWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ep = EntryPointAccessors
            .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)

        val dataStore = ep.encryptedDataStore()
        val deviceDao = ep.deviceDao()

        // Vérifier is_admin avant d'accéder aux données devices (CLAUDE.md §2).
        // readBooleanSafe distingue un datastore corrompu (Keystore invalidé → session à
        // rétablir) d'un compte non-admin — readBoolean() renvoyait null dans les deux cas.
        val access = adminAccessOf(dataStore.readBooleanSafe(DataStoreKeys.IS_ADMIN))

        val devices = if (access == AdminAccess.GRANTED) {
            deviceDao.getAll()
        } else {
            emptyList()
        }

        // Timestamp de la dernière tentative de sync (non sensible — SharedPreferences plain)
        val lastSyncAttempt = WidgetUpdateWorker.readLastSyncAttempt(context)

        provideContent {
            GlanceTheme {
                SystemStatusWidgetContent(
                    access = access,
                    devices = devices,
                    lastSyncAttempt = lastSyncAttempt,
                )
            }
        }
    }
}

/** Résultat du contrôle `is_admin` du widget. */
internal enum class AdminAccess { GRANTED, NOT_ADMIN, SESSION_EXPIRED }

/**
 * `Found(true)` → [AdminAccess.GRANTED] ; `Found(false)` / `NotFound` → [AdminAccess.NOT_ADMIN] ;
 * `Corrupted` → [AdminAccess.SESSION_EXPIRED] (même message que les autres widgets).
 */
internal fun adminAccessOf(result: SecureReadResult<Boolean>): AdminAccess = when (result) {
    is SecureReadResult.Found -> if (result.value) AdminAccess.GRANTED else AdminAccess.NOT_ADMIN
    is SecureReadResult.NotFound -> AdminAccess.NOT_ADMIN
    is SecureReadResult.Corrupted -> AdminAccess.SESSION_EXPIRED
}

@Composable
private fun SystemStatusWidgetContent(
    access: AdminAccess,
    devices: List<DeviceEntity>,
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
        if (access != AdminAccess.GRANTED) {
            Text(
                text = if (access == AdminAccess.SESSION_EXPIRED) {
                    "Session expirée — ouvrez l'app"
                } else {
                    "Réservé aux administrateurs"
                },
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp,
                ),
            )
            return@Column
        }

        // En-tête
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Système",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = GlanceModifier.defaultWeight(),
            )
            // Timestamp synced_at — obligatoire ; « périmé » au-delà de maxOf(DEVICES_MS, WIDGET_STALE_GRACE_MS)
            val syncLabel = if (devices.isNotEmpty()) {
                val label = formatWidgetSyncTime(devices.maxOf { it.syncedAt }, widgetStaleThreshold(CacheTtl.DEVICES_MS))
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

        Spacer(modifier = GlanceModifier.height(4.dp))

        if (devices.isEmpty()) {
            Text(
                text = "Aucun device enregistré",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp,
                ),
            )
            return@Column
        }

        // Compteurs online/offline
        val onlineDevices = devices.count { DeviceStatus.fromApiString(it.status) == DeviceStatus.ONLINE }
        val offlineDevices = devices.size - onlineDevices

        // Couleurs status — cohérentes avec le design system (Emerald/Rose)
        val onlineColor  = WidgetColors.Positive
        val offlineColor = WidgetColors.Negative

        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Online
            Text(
                text = "$onlineDevices",
                style = TextStyle(
                    color = onlineColor.provider(),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Spacer(modifier = GlanceModifier.width(2.dp))
            Text(
                text = "Online",
                style = TextStyle(
                    color = onlineColor.provider(),
                    fontSize = 11.sp,
                ),
            )

            Spacer(modifier = GlanceModifier.width(8.dp))

            // Offline
            Text(
                text = "$offlineDevices",
                style = TextStyle(
                    color = offlineColor.provider(),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Spacer(modifier = GlanceModifier.width(2.dp))
            Text(
                text = "Offline",
                style = TextStyle(
                    color = offlineColor.provider(),
                    fontSize = 11.sp,
                ),
            )
        }

        // Dernier device avec activité récente
        val latestDevice = devices.mapNotNull { d -> d.lastHeartbeat?.let { d to it } }.maxByOrNull { it.second }?.first
        if (latestDevice != null) {
            Spacer(modifier = GlanceModifier.height(2.dp))
            Text(
                text = latestDevice.name ?: latestDevice.id,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 11.sp,
                ),
                maxLines = 1,
            )
        }
    }
}

