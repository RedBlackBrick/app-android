package com.tradingplatform.app.domain.usecase.device

import com.tradingplatform.app.domain.repository.DeviceRepository
import javax.inject.Inject

/**
 * Envoie une commande à un device Radxa via le VPS (POST /v1/edge-control/devices/{id}/commands).
 *
 * @param deviceId identifiant du device cible
 * @param commandType valeur API de la commande ("reboot", "health_check", "update_firmware")
 */
class SendDeviceCommandUseCase @Inject constructor(
    private val repository: DeviceRepository,
) {
    suspend operator fun invoke(deviceId: String, commandType: String, params: Map<String, Any>? = null): Result<Unit> =
        repository.sendCommand(deviceId, commandType, params)
}
