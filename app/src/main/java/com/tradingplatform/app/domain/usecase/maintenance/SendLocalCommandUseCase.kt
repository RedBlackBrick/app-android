package com.tradingplatform.app.domain.usecase.maintenance

import com.tradingplatform.app.domain.repository.LocalMaintenanceRepository
import timber.log.Timber
import javax.inject.Inject

class SendLocalCommandUseCase @Inject constructor(
    private val repository: LocalMaintenanceRepository,
) {
    suspend operator fun invoke(
        deviceIp: String,
        devicePort: Int,
        action: String,
        localToken: String,
        radxaWgPubkey: String,
        params: Map<String, String> = emptyMap(),
    ): Result<String> {
        Timber.d("SendLocalCommand: ip=$deviceIp port=$devicePort action=$action token=[REDACTED]")
        return repository.sendCommand(deviceIp, devicePort, action, localToken, radxaWgPubkey, params)
    }
}
