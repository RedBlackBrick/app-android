package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Corps de l'erreur 401 AUTH_1004 retournée par POST /v1/auth/login
 * quand l'utilisateur a activé le TOTP.
 *
 * Ce DTO est distinct de [ApiErrorDto] car il contient un [sessionToken]
 * supplémentaire requis pour la vérification 2FA.
 */
@JsonClass(generateAdapter = true)
data class TotpRequiredErrorDto(
    @Json(name = "error_code") val errorCode: String,
    @Json(name = "message") val message: String,
    @Json(name = "session_token") val sessionToken: String,
    @Json(name = "success") val success: Boolean = false,
)
