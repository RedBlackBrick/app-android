package com.tradingplatform.app.domain.repository

import com.tradingplatform.app.domain.model.VpnPeer

interface MyDevicesRepository {
    suspend fun getMyPeers(): Result<List<VpnPeer>>
}
