package com.tradingplatform.app.domain.model

import java.time.Instant

data class Alert(
    val id: Long,
    val title: String,
    val body: String,
    val type: AlertType,
    val receivedAt: Instant,
    val read: Boolean,
)
