package com.tradingplatform.app.domain.usecase.maintenance

import com.tradingplatform.app.domain.model.DeviceLocalStatus
import com.tradingplatform.app.domain.repository.LocalMaintenanceRepository
import javax.inject.Inject

class GetLocalStatusUseCase @Inject constructor(
    private val repository: LocalMaintenanceRepository,
) {
    suspend operator fun invoke(
        deviceIp: String,
        devicePort: Int,
    ): Result<DeviceLocalStatus> = repository.getStatus(deviceIp, devicePort)
}
