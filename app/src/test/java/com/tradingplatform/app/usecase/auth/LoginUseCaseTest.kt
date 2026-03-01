package com.tradingplatform.app.usecase.auth

import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.domain.model.AuthTokens
import com.tradingplatform.app.domain.model.User
import com.tradingplatform.app.domain.repository.AuthRepository
import com.tradingplatform.app.domain.usecase.auth.LoginUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LoginUseCaseTest {
    private val authRepository = mockk<AuthRepository>()
    private val dataStore = mockk<EncryptedDataStore>(relaxed = true)
    private lateinit var useCase: LoginUseCase

    private val fakeUser = User(1L, "test@test.com", "John", "Doe", false, false)
    private val fakeTokens = AuthTokens("token123", "bearer", 900)

    @Before
    fun setUp() {
        useCase = LoginUseCase(authRepository, dataStore)
    }

    @Test
    fun `login success stores token and user info`() = runTest {
        coEvery { authRepository.login(any(), any()) } returns Result.success(Pair(fakeUser, fakeTokens))

        val result = useCase("test@test.com", "password123")

        assertTrue(result.isSuccess)
        coVerify { dataStore.writeString(DataStoreKeys.ACCESS_TOKEN, "token123") }
        coVerify { dataStore.writeLong(DataStoreKeys.USER_ID, 1L) }
        coVerify { dataStore.writeBoolean(DataStoreKeys.IS_ADMIN, false) }
    }

    @Test
    fun `login failure does not store anything`() = runTest {
        coEvery { authRepository.login(any(), any()) } returns Result.failure(RuntimeException("401"))

        val result = useCase("test@test.com", "wrong")

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { dataStore.writeString(any(), any()) }
    }

    @Test
    fun `login success with admin user stores is_admin true`() = runTest {
        val adminUser = fakeUser.copy(isAdmin = true)
        coEvery { authRepository.login(any(), any()) } returns Result.success(Pair(adminUser, fakeTokens))

        val result = useCase("admin@test.com", "password123")

        assertTrue(result.isSuccess)
        coVerify { dataStore.writeBoolean(DataStoreKeys.IS_ADMIN, true) }
    }

    @Test
    fun `login returns the user and tokens on success`() = runTest {
        coEvery { authRepository.login(any(), any()) } returns Result.success(Pair(fakeUser, fakeTokens))

        val result = useCase("test@test.com", "password123")

        assertTrue(result.isSuccess)
        val (user, tokens) = result.getOrThrow()
        assertTrue(user.email == "test@test.com")
        assertTrue(tokens.accessToken == "token123")
    }
}
