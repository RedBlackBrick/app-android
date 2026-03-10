package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.MyDevicesApi
import com.tradingplatform.app.data.model.toDomain
import com.tradingplatform.app.domain.model.VpnPeer
import com.tradingplatform.app.domain.repository.MyDevicesRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MyDevicesRepositoryImpl @Inject constructor(
    private val myDevicesApi: MyDevicesApi,
) : MyDevicesRepository {

    override suspend fun getMyPeers(): Result<List<VpnPeer>> = runCatching {
        val response = myDevicesApi.getMyPeers()
        if (!response.isSuccessful) {
            error("Get my peers failed: HTTP ${response.code()}")
        }
        response.body()?.peers?.map { it.toDomain() } ?: emptyList()
    }
}
