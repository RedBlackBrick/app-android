package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Response from POST /v1/auth/2fa/verify (alias for /verify-2fa).
 *
 * Backend returns: { message, user, tokens, session_token }.
 * All fields are nullable for backward compatibility — Moshi silently
 * drops unknown fields, but we now capture the full response so the
 * app can persist tokens after 2FA verification.
 */
@JsonClass(generateAdapter = true)
data class TotpVerifyResponseDto(
    @Json(name = "message") val message: String? = null,
    @Json(name = "user") val user: UserDto? = null,
    @Json(name = "tokens") val tokens: TokenResponseDto? = null,
    @Json(name = "session_token") val sessionToken: String? = null,
)
