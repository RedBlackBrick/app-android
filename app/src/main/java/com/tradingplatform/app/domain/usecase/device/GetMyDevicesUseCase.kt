package com.tradingplatform.app.domain.usecase.device

import com.tradingplatform.app.domain.model.VpnPeer
import com.tradingplatform.app.domain.repository.MyDevicesRepository
import javax.inject.Inject

class GetMyDevicesUseCase @Inject constructor(
    private val repository: MyDevicesRepository,
) {
    suspend operator fun invoke(): Result<List<VpnPeer>> =
        repository.getMyPeers()
}
