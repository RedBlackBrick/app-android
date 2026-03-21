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
                dataStore.remove(DataStoreKeys.PENDING_FCM_TOKEN)
                dataStore.remove(DataStoreKeys.PENDING_FCM_FINGERPRINT)
                Result.success()
            },
            onFailure = { e ->
                when (e) {
                    is VpnNotConnectedException, is IOException -> {
                        Timber.tag(TAG).w(e, "FCM retry — will retry with backoff")
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
