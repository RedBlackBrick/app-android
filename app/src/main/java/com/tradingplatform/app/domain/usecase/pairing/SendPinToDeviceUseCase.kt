package com.tradingplatform.app.domain.usecase.pairing

import com.tradingplatform.app.domain.repository.PairingRepository
import timber.log.Timber
import javax.inject.Inject

class SendPinToDeviceUseCase @Inject constructor(
    private val repository: PairingRepository,
) {
    /**
     * Envoie le PIN de session à la Radxa via LAN.
     * Le session_pin n'est JAMAIS loggé — [REDACTED].
     * Délègue entièrement au PairingRepository (pas d'appel réseau direct dans le UseCase).
     */
    suspend operator fun invoke(
        deviceIp: String,
        devicePort: Int,
        sessionId: String,
        sessionPin: String,
    ): Result<Unit> {
        Timber.d("SendPinToDevice: ip=$deviceIp port=$devicePort sessionId=$sessionId pin=[REDACTED]")
        return repository.sendPin(deviceIp, devicePort, sessionId, sessionPin)
    }
}
