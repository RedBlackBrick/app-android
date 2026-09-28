package com.tradingplatform.app.fcm

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.domain.exception.HttpStatusException
import com.tradingplatform.app.domain.usecase.notification.RegisterFcmTokenUseCase
import com.tradingplatform.app.vpn.VpnNotConnectedException
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that retries FCM token registration with exponential backoff.
 *
 * Triggered when [TradingFirebaseMessagingService.onNewToken] fails to register
 * the token immediately, or on app startup if a pending token is found in
 * [EncryptedDataStore] (crash recovery).
 *
 * Constraints: requires network connectivity.
 * Backoff: exponential starting at 30 seconds.
 */
@HiltWorker
class FcmTokenRegistrationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val dataStore: EncryptedDataStore,
    private val registerFcmTokenUseCase: RegisterFcmTokenUseCase,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val token = dataStore.readString(DataStoreKeys.PENDING_FCM_TOKEN)
        val fingerprint = dataStore.readString(DataStoreKeys.PENDING_FCM_FINGERPRINT)

        if (token == null || fingerprint == null) {
            Timber.tag(TAG).d("No pending FCM token — nothing to do")
            return Result.success()
        }

        return registerFcmTokenUseCase(token, fingerprint).fold(
            onSuccess = {
                Timber.tag(TAG).d("FCM token registered via WorkManager")
                // Compare-and-remove: only wipe the pending keys if they still hold the token
                // we just registered. If onNewToken() rotated them concurrently (a fresher
                // token/fingerprint pair was written while this attempt was in flight), that
                // pending pair must survive so it gets its own registration attempt.
                dataStore.removeIfEquals(DataStoreKeys.PENDING_FCM_TOKEN, token)
                dataStore.removeIfEquals(DataStoreKeys.PENDING_FCM_FINGERPRINT, fingerprint)
                Result.success()
            },
            onFailure = { e ->
                when {
                    e is VpnNotConnectedException || e is IOException -> {
                        Timber.tag(TAG).w(e, "FCM retry — will retry with backoff")
                        Result.retry()
                    }
                    e is HttpStatusException && e.isRetryable -> {
                        Timber.tag(TAG).w(e, "FCM retry — transient HTTP ${e.code}, will retry with backoff")
                        Result.retry()
                    }
                    else -> {
                        Timber.tag(TAG).e(e, "FCM retry — non-retryable error")
                        Result.failure()
                    }
                }
            },
        )
    }

    companion object {
        private const val TAG = "FcmTokenRegWorker"
        private const val UNIQUE_WORK_NAME = "fcm_token_registration"

        /**
         * Enqueues a unique one-time work request for FCM token registration.
         * Uses [ExistingWorkPolicy.REPLACE] so that a newer attempt supersedes
         * any pending/backed-off work.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<FcmTokenRegistrationWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
