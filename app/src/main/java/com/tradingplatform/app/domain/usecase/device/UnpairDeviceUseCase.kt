package com.tradingplatform.app.domain.usecase.device

import com.tradingplatform.app.domain.repository.DeviceRepository
import javax.inject.Inject

class UnpairDeviceUseCase @Inject constructor(
    private val repository: DeviceRepository,
) {
    suspend operator fun invoke(deviceId: String): Result<Unit> =
        repository.unpairDevice(deviceId)
}
