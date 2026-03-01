package com.tradingplatform.app.usecase.auth

import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.domain.repository.AuthRepository
import com.tradingplatform.app.domain.usecase.auth.LogoutUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LogoutUseCaseTest {
    private val authRepository = mockk<AuthRepository>()
    private val dataStore = mockk<EncryptedDataStore>(relaxed = true)
    private lateinit var useCase: LogoutUseCase

    @Before
    fun setUp() {
        useCase = LogoutUseCase(authRepository, dataStore)
    }

    @Test
    fun `logout clears datastore even when API succeeds`() = runTest {
        coEvery { authRepository.logout() } returns Result.success(Unit)

        val result = useCase()

        assertTrue(result.isSuccess)
        coVerify { dataStore.clearAll() }
    }

    @Test
    fun `logout clears datastore even when API fails`() = runTest {
        coEvery { authRepository.logout() } returns Result.failure(RuntimeException("Network error"))

        val result = useCase()

        assertTrue(result.isFailure)
        // clearAll must always be called regardless of API outcome
        coVerify { dataStore.clearAll() }
    }
}
