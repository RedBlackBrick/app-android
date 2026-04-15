package com.tradingplatform.app

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.data.websocket.PrivateWsClient
import com.tradingplatform.app.fcm.FcmTokenRegistrationWorker
import com.tradingplatform.app.widget.WidgetUpdateWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class TradingApplication : Application() {

    /**
     * Injecté par Hilt après super.onCreate() (garantie @HiltAndroidApp).
     * Démarre la connexion WS uniquement si un access token est présent,
     * ce qui indique que l'utilisateur est déjà authentifié.
     * Si l'utilisateur n'est pas connecté, [PrivateWsClient] sera connecté
     * après le login réussi (via [PrivateWsClient.connect] appelé par le LoginViewModel).
     */
    @Inject lateinit var privateWsClient: PrivateWsClient
    @Inject lateinit var encryptedDataStore: EncryptedDataStore
    @Inject lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        initTimber()
        scheduleWidgetUpdateWorker()
        
        // Single launch for all startup checks to minimize EncryptedDataStore/Keystore contention
        appScope.launch {
            // Parallel reads from DataStore
            val hasToken = encryptedDataStore.readString(DataStoreKeys.ACCESS_TOKEN) != null
            val hasPendingFcm = encryptedDataStore.readString(DataStoreKeys.PENDING_FCM_TOKEN) != null

            if (hasToken) {
                Timber.d("TradingApplication: access token found — starting private WS client")
                privateWsClient.connect()
            } else {
                Timber.d("TradingApplication: no access token — WS will connect after login")
            }

            if (hasPendingFcm) {
                Timber.d("Pending FCM token found — scheduling retry")
                FcmTokenRegistrationWorker.enqueue(applicationContext)
            }
        }
    }

    private fun initTimber() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(CrashlyticsTree())
        }
    }

    private fun scheduleWidgetUpdateWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // Minimum WorkManager periodic : 15 min (OS constraint)
        // En pratique les widgets se rafraîchissent toutes les 15 min minimum
        val workRequest = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "widget_update",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }
}
