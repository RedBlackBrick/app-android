package com.tradingplatform.app.fcm

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.domain.exception.HttpStatusException
import com.tradingplatform.app.domain.usecase.notification.RegisterFcmTokenUseCase
import com.tradingplatform.app.vpn.VpnNotConnectedException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Tests unitaires pour [FcmTokenRegistrationWorker] (PR 4.5 — audit #19).
 *
 * Même approche que [com.tradingplatform.app.widget.WidgetUpdateWorkerTest] :
 * TestListenableWorkerBuilder + WorkerFactory manuelle pour injecter des mocks Mockk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FcmTokenRegistrationWorkerTest {

    private val dataStore = mockk<EncryptedDataStore>()
    private val registerFcmTokenUseCase = mockk<RegisterFcmTokenUseCase>()

    private val token = "fcm-token-abc"
    private val fingerprint = "device-fingerprint-123"

    @Before
    fun setUp() {
        coEvery { dataStore.readString(DataStoreKeys.PENDING_FCM_TOKEN) } returns token
        coEvery { dataStore.readString(DataStoreKeys.PENDING_FCM_FINGERPRINT) } returns fingerprint
        coEvery { dataStore.removeIfEquals(any(), any()) } returns true
    }

    private fun buildWorker(): FcmTokenRegistrationWorker {
        return TestListenableWorkerBuilder<FcmTokenRegistrationWorker>(
            ApplicationProvider.getApplicationContext()
        ).setWorkerFactory(
            object : androidx.work.WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: androidx.work.WorkerParameters,
                ): ListenableWorker {
                    return FcmTokenRegistrationWorker(
                        context = appContext,
                        workerParams = workerParameters,
                        dataStore = dataStore,
                        registerFcmTokenUseCase = registerFcmTokenUseCase,
                    )
                }
            }
        ).build()
    }

    // ── No pending token ──────────────────────────────────────────────────────

    @Test
    fun `doWork returns success without calling the use case when no pending token`() = runTest {
        coEvery { dataStore.readString(DataStoreKeys.PENDING_FCM_TOKEN) } returns null

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { registerFcmTokenUseCase(any(), any()) }
    }

    @Test
    fun `doWork returns success without calling the use case when no pending fingerprint`() = runTest {
        coEvery { dataStore.readString(DataStoreKeys.PENDING_FCM_FINGERPRINT) } returns null

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { registerFcmTokenUseCase(any(), any()) }
    }

    // ── HTTP status handling ──────────────────────────────────────────────────

    @Test
    fun `doWork retries on a retryable HTTP 503`() = runTest {
        coEvery { registerFcmTokenUseCase(token, fingerprint) } returns
            Result.failure(HttpStatusException(503, "v1/notifications/fcm-token"))

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `doWork retries on HTTP 429`() = runTest {
        coEvery { registerFcmTokenUseCase(token, fingerprint) } returns
            Result.failure(HttpStatusException(429, "v1/notifications/fcm-token"))

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `doWork retries on HTTP 408`() = runTest {
        coEvery { registerFcmTokenUseCase(token, fingerprint) } returns
            Result.failure(HttpStatusException(408, "v1/notifications/fcm-token"))

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `doWork fails definitively on a non-retryable HTTP 400`() = runTest {
        coEvery { registerFcmTokenUseCase(token, fingerprint) } returns
            Result.failure(HttpStatusException(400, "v1/notifications/fcm-token"))

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
    }

    @Test
    fun `doWork fails definitively on HTTP 401`() = runTest {
        coEvery { registerFcmTokenUseCase(token, fingerprint) } returns
            Result.failure(HttpStatusException(401, "v1/notifications/fcm-token"))

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
    }

    // ── IOException / VPN ─────────────────────────────────────────────────────

    @Test
    fun `doWork retries on IOException`() = runTest {
        coEvery { registerFcmTokenUseCase(token, fingerprint) } returns
            Result.failure(IOException("timeout"))

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `doWork retries when VPN is not connected`() = runTest {
        coEvery { registerFcmTokenUseCase(token, fingerprint) } returns
            Result.failure(VpnNotConnectedException())

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `doWork fails definitively on an unexpected exception`() = runTest {
        coEvery { registerFcmTokenUseCase(token, fingerprint) } returns
            Result.failure(IllegalStateException("unexpected"))

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
    }

    // ── Success — compare-and-remove ──────────────────────────────────────────

    @Test
    fun `doWork removes pending keys only if unchanged on success`() = runTest {
        coEvery { registerFcmTokenUseCase(token, fingerprint) } returns Result.success(Unit)

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { dataStore.removeIfEquals(DataStoreKeys.PENDING_FCM_TOKEN, token) }
        coVerify(exactly = 1) { dataStore.removeIfEquals(DataStoreKeys.PENDING_FCM_FINGERPRINT, fingerprint) }
    }

    @Test
    fun `doWork succeeds even when the pending token rotated concurrently (compare-and-remove no-op)`() = runTest {
        // removeIfEquals returns false: a newer token/fingerprint was written meanwhile.
        // The worker must still report success for THIS registration — it does not know
        // (and does not need to know) about the rotation; the pending pair now holds the
        // new value and will get its own retry via the next onNewToken() call.
        coEvery { registerFcmTokenUseCase(token, fingerprint) } returns Result.success(Unit)
        coEvery { dataStore.removeIfEquals(any(), any()) } returns false

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { dataStore.removeIfEquals(DataStoreKeys.PENDING_FCM_TOKEN, token) }
        coVerify(exactly = 1) { dataStore.removeIfEquals(DataStoreKeys.PENDING_FCM_FINGERPRINT, fingerprint) }
    }
}
