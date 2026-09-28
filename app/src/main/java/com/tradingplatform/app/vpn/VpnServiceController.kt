package com.tradingplatform.app.vpn

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * Starts/stops [WireGuardVpnService], the foreground service that only carries the persistent
 * "VPN connecté" notification. Abstracted so [WireGuardManager] can be tested on the JVM.
 */
interface VpnServiceController {
    /** Starts the notification service in the foreground (before bringing the tunnel UP). */
    fun startForeground()

    /** Removes the notification and stops the service. Idempotent. */
    fun stop()
}

internal class IntentVpnServiceController(private val context: Context) : VpnServiceController {

    override fun startForeground() {
        val intent = Intent(context, WireGuardVpnService::class.java).apply {
            action = WireGuardVpnService.ACTION_CONNECT
        }
        ContextCompat.startForegroundService(context, intent)
    }

    override fun stop() {
        val intent = Intent(context, WireGuardVpnService::class.java).apply {
            action = WireGuardVpnService.ACTION_DISCONNECT
        }
        try {
            context.startService(intent)
        } catch (e: IllegalStateException) {
            // Background-start restriction (API 26+) when the service is no longer running and
            // the app is in background (e.g. OS revocation while backgrounded). stopService()
            // has no such restriction and also removes a foreground notification.
            Timber.tag(TAG).d("ACTION_DISCONNECT not deliverable (${e.javaClass.simpleName}) — stopService")
            context.stopService(Intent(context, WireGuardVpnService::class.java))
        }
    }

    private companion object {
        const val TAG = "VpnServiceController"
    }
}
