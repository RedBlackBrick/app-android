package com.tradingplatform.app.usecase.auth

import com.tradingplatform.app.domain.model.AuthTokens
import com.tradingplatform.app.domain.model.User
import com.tradingplatform.app.domain.repository.AuthRepository
import com.tradingplatform.app.domain.usecase.auth.LoginUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LoginUseCaseTest {
    private val authRepository = mockk<AuthRepository>()
    private lateinit var useCase: LoginUseCase

    private val fakeUser = User(1L, "test@test.com", "John", "Doe", false, false)
    private val fakeTokens = AuthTokens("token123", "bearer", 900)

    @Before
    fun setUp() {
        useCase = LoginUseCase(authRepository)
    }

    @Test
    fun `login success propagates result`() = runTest {
        coEvery { authRepository.login(any(), any()) } returns Result.success(Pair(fakeUser, fakeTokens))

        val result = useCase("test@test.com", "password123")

        assertTrue(result.isSuccess)
    }

    @Test
    fun `login failure propagates result`() = runTest {
        coEvery { authRepository.login(any(), any()) } returns Result.failure(RuntimeException("401"))

        val result = useCase("test@test.com", "wrong")

        assertTrue(result.isFailure)
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

    @Test
    fun `login success with admin user propagates admin flag`() = runTest {
        val adminUser = fakeUser.copy(isAdmin = true)
        coEvery { authRepository.login(any(), any()) } returns Result.success(Pair(adminUser, fakeTokens))

        val result = useCase("admin@test.com", "password123")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().first.isAdmin)
    }
}
