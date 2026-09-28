package com.tradingplatform.app.usecase.auth

import com.tradingplatform.app.data.api.interceptor.CsrfInterceptor
import com.tradingplatform.app.data.api.interceptor.EncryptedCookieJar
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.data.local.db.AppDatabase
import com.tradingplatform.app.data.session.TokenHolder
import com.tradingplatform.app.data.websocket.PrivateWsClient
import com.tradingplatform.app.domain.usecase.auth.RecoverFromKeystoreCorruptionUseCase
import com.tradingplatform.app.security.BiometricLockManager
import com.tradingplatform.app.vpn.WireGuardManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecoverFromKeystoreCorruptionUseCaseTest {

    private val privateWsClient = mockk<PrivateWsClient>(relaxed = true)
    private val wireGuardManager = mockk<WireGuardManager>(relaxed = true)
    private val tokenHolder = TokenHolder()
    private val cookieJar = mockk<EncryptedCookieJar>(relaxed = true)
    private val csrfInterceptor = mockk<CsrfInterceptor>(relaxed = true)
    private val appDatabase = mockk<AppDatabase>(relaxed = true)
    private val dataStore = mockk<EncryptedDataStore>(relaxed = true)
    private val biometricLockManager = mockk<BiometricLockManager>(relaxed = true)

    private lateinit var useCase: RecoverFromKeystoreCorruptionUseCase

    @Before
    fun setUp() {
        tokenHolder.setToken("stale-token")
        useCase = RecoverFromKeystoreCorruptionUseCase(
            privateWsClient = privateWsClient,
            wireGuardManager = wireGuardManager,
            tokenHolder = tokenHolder,
            cookieJar = cookieJar,
            csrfInterceptor = csrfInterceptor,
            appDatabase = appDatabase,
            dataStore = dataStore,
            biometricLockManager = biometricLockManager,
        )
    }

    @Test
    fun `full teardown then reset returns true and unlocks`() = runTest {
        coEvery { dataStore.resetCorruptedStore() } returns true

        val result = useCase()

        assertTrue(result)
        assertNull(tokenHolder.accessToken)
        coVerifyOrder {
            privateWsClient.disconnect()
            wireGuardManager.disconnect()
            cookieJar.clear()
            csrfInterceptor.clearToken()
            appDatabase.clearAllTables()
            dataStore.resetCorruptedStore()
            biometricLockManager.unlock()
        }
    }

    @Test
    fun `reset failure returns false and keeps biometric lock`() = runTest {
        coEvery { dataStore.resetCorruptedStore() } returns false

        val result = useCase()

        assertFalse(result)
        coVerify(exactly = 1) { dataStore.resetCorruptedStore() }
        verify(exactly = 0) { biometricLockManager.unlock() }
    }

    @Test
    fun `Room failure does not prevent the datastore reset`() = runTest {
        every { appDatabase.clearAllTables() } throws IllegalStateException("db closed")
        coEvery { dataStore.resetCorruptedStore() } returns true

        assertTrue(useCase())
        coVerify(exactly = 1) { dataStore.resetCorruptedStore() }
    }

    @Test
    fun `WS and VPN disconnect failures do not abort recovery`() = runTest {
        every { privateWsClient.disconnect() } throws RuntimeException("ws")
        every { wireGuardManager.disconnect() } throws RuntimeException("vpn")
        coEvery { dataStore.resetCorruptedStore() } returns true

        assertTrue(useCase())
        coVerify(exactly = 1) { dataStore.resetCorruptedStore() }
    }
}
