package com.tradingplatform.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import com.tradingplatform.app.MainActivity
import timber.log.Timber

/**
 * Foreground service that keeps the WireGuard tunnel alive.
 *
 * This service is declared in AndroidManifest.xml with:
 *   - `android:foregroundServiceType="specialUse"` (required Android 14+/API 34)
 *   - `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property explaining the VPN use case
 *   - `android.permission.BIND_VPN_SERVICE` permission
 *
 * Lifecycle:
 *   - [ACTION_CONNECT] → calls [startForeground] with a persistent VPN notification
 *   - [ACTION_DISCONNECT] → removes the foreground notification and stops the service
 *
 * The actual tunnel setup (key exchange, routing) is handled by [WireGuardManager]
 * via the wireguard-android GoBackend. This service provides the Android VPN framework
 * lifecycle wrapper required by the OS.
 */
class WireGuardVpnService : VpnService() {

    companion object {
        private const val TAG = "WireGuardVpnService"
        const val ACTION_CONNECT = "com.tradingplatform.app.vpn.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.tradingplatform.app.vpn.ACTION_DISCONNECT"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "vpn_status"
        private const val CHANNEL_NAME = "État VPN"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_CONNECT -> {
                startForeground(NOTIFICATION_ID, buildNotification("VPN connecté"))
                Timber.tag(TAG).i("WireGuardVpnService: foreground started (CONNECT)")
                START_STICKY
            }
            ACTION_DISCONNECT -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                Timber.tag(TAG).i("WireGuardVpnService: stopped (DISCONNECT)")
                START_NOT_STICKY
            }
            else -> {
                Timber.tag(TAG).w("WireGuardVpnService: unknown action — ${intent?.action}")
                START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.tag(TAG).i("WireGuardVpnService: destroyed")
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun buildNotification(statusText: String): Notification {
        val mainIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return android.app.Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Trading Platform")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Statut du tunnel WireGuard"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
