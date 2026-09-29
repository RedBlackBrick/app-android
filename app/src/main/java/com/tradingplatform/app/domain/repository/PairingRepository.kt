package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.PairingStatus
import kotlinx.coroutines.flow.Flow

interface PairingRepository {
    /**
     * Envoie le PIN chiffré à la Radxa. Succès = le device a accepté (HTTP 2xx) ; la valeur est le
     * `device_id` **définitif** renvoyé par la Radxa (celui alloué par le VPS), ou `null` s'il est
     * absent — l'appelant retombe alors sur l'id provisoire lu dans le QR Radxa.
     */
    suspend fun sendPin(
        deviceIp: String,
        devicePort: Int,
        sessionId: String,
        sessionPin: String,
        localToken: String,
        nonce: String,
        radxaWgPubkey: String,
    ): Result<String?>

    fun pollStatus(
        deviceIp: String,
        devicePort: Int,
        sessionId: String,
    ): Flow<PairingStatus>
}
