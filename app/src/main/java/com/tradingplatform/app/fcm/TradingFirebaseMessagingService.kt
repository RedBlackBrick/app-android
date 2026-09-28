package com.tradingplatform.app.fcm

import android.Manifest
import android.annotation.SuppressLint
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
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
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
import kotlinx.coroutines.runBlocking
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

    private val encryptedDataStore: EncryptedDataStore by lazy {
        EntryPointAccessors
            .fromApplication(applicationContext, FcmEntryPoint::class.java)
            .encryptedDataStore()
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
        val rawTitle = message.notification?.title ?: message.data["title"] ?: "Alerte"
        val rawBody = message.notification?.body ?: message.data["body"] ?: ""
        val title = rawTitle.take(MAX_TITLE_LENGTH)
        val body = rawBody.take(MAX_BODY_LENGTH)
        val typeStr = message.data["type"] ?: "UNKNOWN"
        val alertType = AlertType.fromString(typeStr)
        val receivedAt = System.currentTimeMillis()

        // Debug uniquement — jamais le contenu de l'alerte
        Timber.tag(TAG).d("FCM reçu type=$alertType")

        // id = 0L : Room génère l'ID automatiquement via autoGenerate = true
        val entity = AlertEntity(
            id = 0L,
            title = title,
            body = body,
            type = alertType.name,
            receivedAt = receivedAt,
            read = false,
            syncedAt = receivedAt,
        )

        // 2. Persister dans Room de manière synchrone (bloquante) avant de continuer.
        //    onMessageReceived() est déjà invoqué par le SDK Firebase sur un thread de fond
        //    dédié (jamais le thread main) — bloquer ici ne risque donc pas d'ANR. On préfère
        //    runBlocking à serviceScope.launch { } (fire-and-forget) car ce dernier peut perdre
        //    l'insert si le système détruit le service (onDestroy → serviceScope.cancel()) juste
        //    après la livraison du message, avant que la coroutine lancée n'ait eu la main —
        //    scénario plausible vu la durée de vie courte du service sur un message isolé.
        //    L'insert est rapide (une ligne Room) donc le coût du blocage est négligeable.
        runBlocking(Dispatchers.IO) {
            alertDao.insert(entity)
        }

        // 3. Afficher la notification avec PendingIntent vers MainActivity (deep link alerts).
        //    L'insert Room ci-dessus est déjà terminé : plus de race entre le tap sur la
        //    notification et l'écriture en base.
        showNotification(title, body, notificationId(alertType.name, receivedAt, title, body))
    }

    // ANDROID_ID is read here only as a stable per-device fingerprint accompanying FCM token
    // registration (server-side dedup/rotation of stale tokens on reinstall) — never used for
    // advertising/analytics or cross-app tracking, so the general HardwareIds guidance (use an
    // advertising/analytics ID instead) does not apply to this fraud/registration use case.
    @SuppressLint("HardwareIds")
    override fun onNewToken(token: String) {
        // Token FCM renouvelé — jamais logger le token en clair
        Timber.tag(TAG).d("FCM token renouvelé : [REDACTED]")

        val deviceFingerprint = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID,
        )?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()

        // Write-ahead: persist token + fingerprint BEFORE attempting registration.
        // On crash/kill between write-ahead and registration, TradingApplication.onCreate()
        // will detect the pending keys and schedule a WorkManager retry.
        serviceScope.launch {
            encryptedDataStore.writeString(DataStoreKeys.PENDING_FCM_TOKEN, token)
            encryptedDataStore.writeString(DataStoreKeys.PENDING_FCM_FINGERPRINT, deviceFingerprint)

            registerFcmTokenUseCase(token, deviceFingerprint)
                .onSuccess {
                    Timber.tag(TAG).d("FCM token enregistré auprès du backend : [REDACTED]")
                    // Compare-and-remove: n'efface les clés pending que si elles contiennent
                    // toujours ce même token/fingerprint. Si onNewToken() a été ré-invoqué entre
                    // temps (rotation du token pendant cet appel réseau), la nouvelle paire
                    // écrite doit survivre pour être enregistrée à son tour.
                    encryptedDataStore.removeIfEquals(DataStoreKeys.PENDING_FCM_TOKEN, token)
                    encryptedDataStore.removeIfEquals(DataStoreKeys.PENDING_FCM_FINGERPRINT, deviceFingerprint)
                }
                .onFailure { e ->
                    Timber.tag(TAG).w(e, "FCM registration failed — scheduling retry")
                    FcmTokenRegistrationWorker.enqueue(applicationContext)
                }
        }
    }

    private fun showNotification(title: String, body: String, id: Int) {
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
                Timber.tag(TAG).w("POST_NOTIFICATIONS permission not granted — notification skipped")
                return
            }
        }

        NotificationManagerCompat.from(this)
            .notify(id, notification)
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
        private const val TAG = "TradingFcmService"
        private const val CHANNEL_ID = "trading_alerts"

        // Longueurs raisonnables avant persistance Room / affichage — un payload FCM
        // malveillant ou mal formé ne doit pas pousser un titre/corps arbitrairement long
        // en base ou dans la notification système.
        const val MAX_TITLE_LENGTH = 120
        const val MAX_BODY_LENGTH = 500

        /**
         * ID de notification stable et déterministe pour un message donné.
         *
         * Remplace `System.currentTimeMillis().toInt()` : tronquer un timestamp en Int perd
         * les bits de poids fort (troncature silencieuse, collisions possibles tous les
         * ~49 jours) et, surtout, ne correspond à aucune donnée persistée — deux appels
         * séparés (ici vs. l'entité Room) produisaient des identifiants sans lien entre eux.
         * Un hash des champs du message (type, receivedAt, title, body) est déterministe :
         * un même message FCM (mêmes champs, même instant de réception) produit toujours le
         * même ID, ce qui permet à `NotificationManager` de remplacer plutôt que dupliquer
         * une notification si jamais le même message était traité deux fois.
         *
         * Fonction pure (pas de dépendance Android) — testable en JVM sans Robolectric.
         */
        fun notificationId(type: String, receivedAt: Long, title: String, body: String): Int =
            "$type|$receivedAt|$title|$body".hashCode()
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
    fun encryptedDataStore(): EncryptedDataStore
}
