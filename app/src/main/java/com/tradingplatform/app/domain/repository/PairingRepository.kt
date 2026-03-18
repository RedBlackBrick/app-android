package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.PairingStatus
import kotlinx.coroutines.flow.Flow

interface PairingRepository {
    suspend fun sendPin(
        deviceIp: String,
        devicePort: Int,
        sessionId: String,
        sessionPin: String,
        localToken: String,
        nonce: String,
        radxaWgPubkey: String,
    ): Result<Unit>

    fun pollStatus(
        deviceIp: String,
        devicePort: Int,
        sessionId: String,
    ): Flow<PairingStatus>
}
