package com.tradingplatform.app.usecase.auth

import com.tradingplatform.app.domain.model.AuthTokens
import com.tradingplatform.app.domain.model.User
import com.tradingplatform.app.domain.repository.AuthRepository
import com.tradingplatform.app.domain.usecase.auth.Verify2faUseCase
import com.tradingplatform.app.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Verify2faUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<AuthRepository>()
    private lateinit var useCase: Verify2faUseCase

    private val fakeUser = User(
        id = 1L,
        email = "test@example.com",
        firstName = "Test",
        lastName = "User",
        isAdmin = false,
        totpEnabled = true,
    )
    private val fakeTokens = AuthTokens(
        accessToken = "access-token-xyz",
        tokenType = "bearer",
        expiresIn = 1800,
    )

    @Before
    fun setUp() {
        useCase = Verify2faUseCase(repository)
    }

    @Test
    fun `verify2fa success returns Result success`() = runTest {
        coEvery { repository.verify2fa("session-token", "123456") } returns
            Result.success(Pair(fakeUser, fakeTokens))

        val result = useCase("session-token", "123456")

        assertTrue("Expected Result.success", result.isSuccess)
    }

    @Test
    fun `verify2fa failure returns Result failure`() = runTest {
        coEvery { repository.verify2fa(any(), any()) } returns
            Result.failure(Exception("Invalid TOTP code"))

        val result = useCase("session-token", "000000")

        assertTrue("Expected Result.failure", result.isFailure)
    }

    @Test
    fun `verify2fa failure contains the original exception`() = runTest {
        val exception = Exception("Invalid TOTP code")
        coEvery { repository.verify2fa(any(), any()) } returns Result.failure(exception)

        val result = useCase("session-token", "000000")

        assertFalse(result.isSuccess)
        assertTrue(
            "Expected original exception message",
            result.exceptionOrNull()?.message == "Invalid TOTP code",
        )
    }

    @Test
    fun `verify2fa delegates to repository with correct args`() = runTest {
        val sessionToken = "abc-session-token"
        val totpCode = "654321"
        coEvery { repository.verify2fa(sessionToken, totpCode) } returns
            Result.success(Pair(fakeUser, fakeTokens))

        useCase(sessionToken, totpCode)

        coVerify(exactly = 1) { repository.verify2fa(sessionToken, totpCode) }
    }

    @Test
    fun `verify2fa with empty code returns failure`() = runTest {
        coEvery { repository.verify2fa(any(), "") } returns
            Result.failure(IllegalArgumentException("TOTP code cannot be empty"))

        val result = useCase("session-token", "")

        assertTrue("Expected failure for empty code", result.isFailure)
    }

    @Test
    fun `verify2fa success returns user and tokens`() = runTest {
        coEvery { repository.verify2fa("session-token", "123456") } returns
            Result.success(Pair(fakeUser, fakeTokens))

        val result = useCase("session-token", "123456")

        assertTrue(result.isSuccess)
        val (user, tokens) = result.getOrThrow()
        assertTrue("Expected user email", user.email == "test@example.com")
        assertTrue("Expected access token", tokens.accessToken == "access-token-xyz")
    }
}
