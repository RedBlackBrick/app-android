package com.tradingplatform.app.fcm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.tradingplatform.app.MainActivity
import com.tradingplatform.app.data.local.db.dao.AlertDao
import com.tradingplatform.app.data.local.db.entity.AlertEntity
import com.tradingplatform.app.domain.model.AlertType
import com.tradingplatform.app.domain.usecase.notification.RegisterFcmTokenUseCase
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID

class TradingFirebaseMessagingService : FirebaseMessagingService() {

    // Injection manuelle via EntryPointAccessors
    // FirebaseMessagingService n'est pas @AndroidEntryPoint — utiliser EntryPointAccessors
    private val alertDao: AlertDao by lazy {
        EntryPointAccessors
            .fromApplication(applicationContext, FcmEntryPoint::class.java)
            .alertDao()
    }

    private val registerFcmTokenUseCase: RegisterFcmTokenUseCase by lazy {
        EntryPointAccessors
            .fromApplication(applicationContext, FcmEntryPoint::class.java)
            .registerFcmTokenUseCase()
    }

    /**
     * Scope dédié au service — annulé dans [onDestroy] pour éviter les fuites mémoire.
     * Ne pas utiliser runBlocking sur le main thread (ANR risk).
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // 1. Parser le message FCM — notification payload ou data payload
        val title = message.notification?.title ?: message.data["title"] ?: "Alerte"
        val body = message.notification?.body ?: message.data["body"] ?: ""
        val typeStr = message.data["type"] ?: "UNKNOWN"
        val alertType = AlertType.fromString(typeStr)

        // Debug uniquement — jamais le contenu de l'alerte
        Timber.d("FCM reçu type=$alertType")

        // 2. Persister dans Room via serviceScope — ne jamais bloquer le main thread (ANR).
        //    showNotification est appelé immédiatement sans attendre l'insert Room.
        //    La race condition (tap sur notif avant que Room écrive) est de quelques ms — acceptable.
        // id = 0L : Room génère l'ID automatiquement via autoGenerate = true
        val entity = AlertEntity(
            id = 0L,
            title = title,
            body = body,
            type = alertType.name,
            receivedAt = System.currentTimeMillis(),
            read = false,
            syncedAt = System.currentTimeMillis(),
        )

        serviceScope.launch {
            alertDao.insert(entity)
        }

        // 3. Afficher la notification avec PendingIntent vers MainActivity (deep link alerts)
        showNotification(title, body)
    }

    override fun onNewToken(token: String) {
        // Token FCM renouvelé — jamais logger le token en clair
        Timber.d("FCM token renouvelé : [REDACTED]")

        val deviceFingerprint = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID,
        )?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()

        serviceScope.launch {
            registerFcmTokenUseCase(token, deviceFingerprint)
                .onSuccess { Timber.d("FCM token enregistré auprès du backend : [REDACTED]") }
                .onFailure { e -> Timber.w(e, "FCM token registration failed — will retry on next token refresh") }
        }
    }

    private fun showNotification(title: String, body: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_NAVIGATE_TO, "alerts")
        }
        // FLAG_IMMUTABLE obligatoire sur API 31+
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = CHANNEL_ID
        createNotificationChannel(channelId)

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        // Android 13+ (API 33) requires POST_NOTIFICATIONS runtime permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Timber.w("POST_NOTIFICATIONS permission not granted — notification skipped")
                return
            }
        }

        NotificationManagerCompat.from(this)
            .notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun createNotificationChannel(channelId: String) {
        val channel = NotificationChannel(
            channelId,
            "Alertes Trading",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alertes de trading en temps réel"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "trading_alerts"
    }
}

/**
 * EntryPoint Hilt pour TradingFirebaseMessagingService.
 *
 * FirebaseMessagingService n'est pas un composant Android supporté par @AndroidEntryPoint.
 * Utiliser EntryPointAccessors.fromApplication() pour accéder au graphe Hilt.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface FcmEntryPoint {
    fun alertDao(): AlertDao
    fun registerFcmTokenUseCase(): RegisterFcmTokenUseCase
}
