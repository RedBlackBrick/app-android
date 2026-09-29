package com.tradingplatform.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.tradingplatform.app.data.api.interceptor.CsrfInterceptor
import com.tradingplatform.app.data.api.interceptor.EncryptedCookieJar
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.data.local.datastore.SecureReadResult
import com.tradingplatform.app.data.session.SessionManager
import com.tradingplatform.app.data.session.TokenHolder
import com.tradingplatform.app.data.websocket.PrivateWsClient
import com.tradingplatform.app.fcm.FcmTokenRegistrationWorker
import com.tradingplatform.app.security.BiometricLockManager
import com.tradingplatform.app.widget.WidgetUpdateWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class TradingApplication : Application(), Configuration.Provider {

    // Injecté par Hilt — permet à WorkManager d'instancier les workers annotés
    // @HiltWorker (comme WidgetUpdateWorker). Sans ça, WorkManager cherche un
    // constructeur (Context, WorkerParameters) et échoue avec NoSuchMethodException.
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()


    /**
     * Injecté par Hilt après super.onCreate() (garantie @HiltAndroidApp).
     * Démarre la connexion WS uniquement si un access token est présent,
     * ce qui indique que l'utilisateur est déjà authentifié.
     * Si l'utilisateur n'est pas connecté, [PrivateWsClient] se connecte de lui-même après le
     * login / la vérification 2FA réussis : AuthRepositoryImpl émet
     * [SessionManager.notifySessionStarted], que [PrivateWsClient] collecte.
     */
    @Inject lateinit var privateWsClient: PrivateWsClient
    @Inject lateinit var encryptedDataStore: EncryptedDataStore
    @Inject lateinit var appScope: CoroutineScope
    @Inject lateinit var tokenHolder: TokenHolder
    @Inject lateinit var sessionManager: SessionManager
    @Inject lateinit var cookieJar: EncryptedCookieJar
    @Inject lateinit var csrfInterceptor: CsrfInterceptor
    @Inject lateinit var biometricLockManager: BiometricLockManager

    override fun onCreate() {
        super.onCreate()
        initTimber()
        scheduleWidgetUpdateWorker()

        // Verrou biométrique : observe le cycle de vie du process (onStart → re-verrouille si
        // > 5 min d'inactivité + polling ; onStop → persiste). onCreate tourne sur le main
        // thread, exigé par addObserver.
        ProcessLifecycleOwner.get().lifecycle.addObserver(biometricLockManager)

        // Pré-chargement synchrone des caches mémoire (token + cookies) pour éviter
        // les runBlocking sur le thread OkHttp au cold start. Le premier appel réseau
        // doit trouver TokenHolder et EncryptedCookieJar déjà peuplés.
        appScope.launch {
            // DEV_MODE (émulateur, jamais en release) : pas de QR d'onboarding à scanner — on marque
            // le setup comme fait pour arriver directement sur l'écran de connexion.
            if (BuildConfig.DEV_MODE) encryptedDataStore.writeBoolean(DataStoreKeys.SETUP_COMPLETED, true)
            val tokenResult = encryptedDataStore.readStringSafe(DataStoreKeys.ACCESS_TOKEN)
            // Politique de verrou au démarrage à froid (D7) — dans la même coroutine que la
            // lecture du token : BiometricLockManager.isLocked vaut true par défaut (fail-closed).
            // - session existante → restore AVANT tokenHolder.setToken (verrouillé sauf si
            //   l'état persisté est "déverrouillé" avec une interaction < 5 min) ;
            // - pas de session → déverrouillage silencieux (Setup/Login ne sont pas protégés) ;
            // - Keystore corrompu → reste verrouillé ; RecoverFromKeystoreCorruptionUseCase
            //   déverrouille après le reset.
            val hasToken = when (tokenResult) {
                is SecureReadResult.Found -> {
                    biometricLockManager.restorePersistedState()
                    // Ne jamais écraser un token plus frais : un refresh (TokenAuthenticator) ou
                    // GetAuthContextUseCase a pu peupler le holder avant la fin de cette lecture.
                    if (tokenHolder.accessToken == null) {
                        tokenHolder.setToken(tokenResult.value)
                        Timber.d("TradingApplication: access token preloaded into TokenHolder")
                    } else {
                        Timber.d("TradingApplication: TokenHolder already populated — preload skipped")
                    }
                    true
                }
                is SecureReadResult.NotFound -> {
                    biometricLockManager.unlock()
                    false
                }
                is SecureReadResult.Corrupted -> {
                    Timber.e(tokenResult.cause, "TradingApplication: Keystore corrupted at startup")
                    sessionManager.notifyKeystoreCorruption()
                    false
                }
            }

            cookieJar.preload()

            val hasPendingFcm = encryptedDataStore.readString(DataStoreKeys.PENDING_FCM_TOKEN) != null

            if (hasToken) {
                Timber.d("TradingApplication: access token found — starting private WS client")
                privateWsClient.connect()
                // Pré-fetch CSRF pour que le premier POST/PUT/DELETE ne bloque pas un thread
                // OkHttp avec un runBlocking synchrone.
                csrfInterceptor.preFetch()
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
