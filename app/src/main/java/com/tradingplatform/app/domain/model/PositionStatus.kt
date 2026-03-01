package com.tradingplatform.app.domain.model

enum class PositionStatus {
    OPEN, CLOSED, ALL;

    fun toApiString(): String = name.lowercase()
}
