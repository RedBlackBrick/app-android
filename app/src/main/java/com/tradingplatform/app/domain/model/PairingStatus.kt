package com.tradingplatform.app.domain.model

enum class PairingStatus {
    PENDING, PAIRED, FAILED;

    companion object {
        /**
         * Mappe les statuts réels du Radxa (pairing-server.py) et du COMMUNICATIONS.md
         * vers les 3 états internes de l'app.
         *
         * Radxa émet : "unpaired", "pairing", "paired", "error"
         * COMMUNICATIONS.md documente : "waiting", "paired", "failed"
         */
        fun fromString(value: String): PairingStatus = when (value.lowercase()) {
            "pending", "unpaired", "pairing", "waiting" -> PENDING
            "paired" -> PAIRED
            "failed", "error" -> FAILED
            else -> PENDING
        }
    }
}
