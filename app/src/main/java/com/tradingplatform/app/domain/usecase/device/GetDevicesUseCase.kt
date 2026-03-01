package com.tradingplatform.app.domain.usecase.device

import com.tradingplatform.app.domain.model.Device
import com.tradingplatform.app.domain.repository.DeviceRepository
import javax.inject.Inject

class GetDevicesUseCase @Inject constructor(
    private val repository: DeviceRepository,
) {
    suspend operator fun invoke(): Result<List<Device>> =
        repository.getDevices()
}
