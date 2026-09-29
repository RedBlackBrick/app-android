package com.tradingplatform.app.data.repository

import com.squareup.moshi.Moshi
import com.tradingplatform.app.data.api.AuthApi
import com.tradingplatform.app.data.api.interceptor.CsrfInterceptor
import com.tradingplatform.app.data.api.interceptor.EncryptedCookieJar
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.data.model.LoginResponseDto
import com.tradingplatform.app.data.session.SessionManager
import com.tradingplatform.app.data.session.TokenHolder
import com.tradingplatform.app.domain.exception.AccountLockedException
import com.tradingplatform.app.domain.exception.InvalidCredentialsException
import com.tradingplatform.app.domain.exception.TotpRequiredException
import com.tradingplatform.app.domain.repository.PortfolioSelectionRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * Corps d'erreur de `POST /v1/auth/login`. Le handler global du backend (`main.py:1304-1333`)
 * renvoie `{success, error_code, message, detail}` à la racine ; `detail` est tantôt un objet
 * (HTTPException(detail=dict)), tantôt une chaîne. Avant la correction, le corps était d'abord
 * parsé comme `TotpRequiredErrorDto` (`session_token` obligatoire) : un 401 sans `session_token`
 * lançait, le `catch` renvoyait null et l'utilisateur voyait « Login failed: HTTP 401 ».
 */
class AuthRepositoryImplLoginErrorTest {

    private val authApi = mockk<AuthApi>()
    private val repository = AuthRepositoryImpl(
        authApi = authApi,
        tokenHolder = mockk<TokenHolder>(relaxed = true),
        dataStore = mockk<EncryptedDataStore>(relaxed = true),
        moshi = Moshi.Builder().build(),
        csrfInterceptor = mockk<CsrfInterceptor>(relaxed = true),
        cookieJar = mockk<EncryptedCookieJar>(relaxed = true),
        okHttpClient = OkHttpClient(),
        sessionManager = mockk<SessionManager>(relaxed = true),
        portfolioSelectionRepository = mockk<PortfolioSelectionRepository>(relaxed = true),
    )

    private fun errorResponse(code: Int, body: String, retryAfter: String? = null): Response<LoginResponseDto> {
        val raw = okhttp3.Response.Builder()
            .request(Request.Builder().url("https://10.42.0.1/v1/auth/login").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("error")
            .apply { retryAfter?.let { header("Retry-After", it) } }
            .body("".toResponseBody(null))
            .build()
        return Response.error(body.toResponseBody("application/json".toMediaType()), raw)
    }

    private suspend fun loginFailure(response: Response<LoginResponseDto>): Throwable {
        coEvery { authApi.login(any()) } returns response
        return repository.login("a@b.c", "pw").exceptionOrNull()
            ?: throw AssertionError("login() unexpectedly succeeded")
    }

    @Test
    fun `401 INVALID_CREDENTIALS without session_token is InvalidCredentials (detail as object)`() = runTest {
        val body = """{"success":false,"error_code":"INVALID_CREDENTIALS","message":"Invalid credentials",
            |"detail":{"message":"Invalid credentials","error_code":"INVALID_CREDENTIALS"}}""".trimMargin()

        assertTrue(loginFailure(errorResponse(401, body)) is InvalidCredentialsException)
    }

    @Test
    fun `401 INVALID_CREDENTIALS with a string detail is InvalidCredentials`() = runTest {
        val body = """{"success":false,"error_code":"INVALID_CREDENTIALS","message":"Invalid credentials",
            |"detail":"Invalid credentials"}""".trimMargin()

        assertTrue(loginFailure(errorResponse(401, body)) is InvalidCredentialsException)
    }

    @Test
    fun `legacy AUTH_1001 code is still InvalidCredentials`() = runTest {
        val body = """{"error_code":"AUTH_1001","message":"bad"}"""

        assertTrue(loginFailure(errorResponse(401, body)) is InvalidCredentialsException)
    }

    @Test
    fun `AUTH_1004 with a session_token is still TotpRequired`() = runTest {
        val body = """{"error_code":"AUTH_1004","message":"2FA","session_token":"tmp-token"}"""

        val failure = loginFailure(errorResponse(401, body))

        assertTrue(failure is TotpRequiredException)
        assertEquals("tmp-token", (failure as TotpRequiredException).sessionToken)
    }

    @Test
    fun `429 reads the integer Retry-After forwarded by the backend`() = runTest {
        val body = """{"success":false,"error_code":"LOGIN_RATE_LIMITED","message":"Too many attempts",
            |"detail":{"error_code":"LOGIN_RATE_LIMITED","retry_after":900}}""".trimMargin()

        val failure = loginFailure(errorResponse(429, body, retryAfter = "900"))

        assertTrue(failure is AccountLockedException)
        assertEquals(900, (failure as AccountLockedException).retryAfterSeconds)
    }

    @Test
    fun `429 without a Retry-After header is still AccountLocked with no countdown`() = runTest {
        val failure = loginFailure(errorResponse(429, """{"detail":"Too many requests"}"""))

        assertTrue(failure is AccountLockedException)
        assertEquals(null, (failure as AccountLockedException).retryAfterSeconds)
    }

    @Test
    fun `unrecognised error code falls back to the generic HTTP error`() = runTest {
        val body = """{"success":false,"error_code":"VALIDATION_ERROR","message":"Validation error"}"""

        assertEquals("Login failed: HTTP 422", loginFailure(errorResponse(422, body)).message)
    }

    @Test
    fun `non-JSON error body falls back to the generic HTTP error`() = runTest {
        assertEquals("Login failed: HTTP 502", loginFailure(errorResponse(502, "<html>Bad Gateway</html>")).message)
    }
}
