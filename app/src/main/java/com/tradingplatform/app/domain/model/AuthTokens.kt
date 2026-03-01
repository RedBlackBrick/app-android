package com.tradingplatform.app.domain.model

data class AuthTokens(
    val accessToken: String,
    val tokenType: String,
    val expiresIn: Int,
)
