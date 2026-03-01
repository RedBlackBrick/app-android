package com.tradingplatform.app.domain.model

enum class PairingStatus {
    PENDING, PAIRED, FAILED;

    companion object {
        fun fromString(value: String): PairingStatus = entries.firstOrNull {
            it.name.equals(value, ignoreCase = true)
        } ?: FAILED
    }
}
