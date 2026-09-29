package com.tradingplatform.app.domain.model

enum class PairingStatus {
    PENDING, PAIRED, FAILED;

    companion object {
        /**
         * Mappe les statuts réels du Radxa (pairing-server.py) et du COMMUNICATIONS.md
         * vers les 3 états internes de l'app.
         *
         * Radxa émet : "unpaired", "pairing", "paired", "error" (+ "waiting" sur 93fdc58, écrit
         * par le HAT) et "unknown" (session_id différent).
         * COMMUNICATIONS.md documente : "waiting", "paired", "failed"
         *
         * "unknown" reste PENDING ici : c'est `PairingRepositoryImpl.pollStatus` qui le compte et
         * le transforme en FAILED après plusieurs réponses consécutives (un seul est tolérable).
         */
        fun fromString(value: String): PairingStatus = when (value.lowercase()) {
            "pending", "unpaired", "pairing", "waiting" -> PENDING
            "paired" -> PAIRED
            "failed", "error" -> FAILED
            else -> PENDING
        }
    }
}
