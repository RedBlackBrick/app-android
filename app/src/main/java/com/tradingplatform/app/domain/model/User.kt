package com.tradingplatform.app.domain.model

data class User(
    val id: Long,
    val email: String,
    val firstName: String,
    val lastName: String,
    val isAdmin: Boolean,
    val totpEnabled: Boolean,
)
