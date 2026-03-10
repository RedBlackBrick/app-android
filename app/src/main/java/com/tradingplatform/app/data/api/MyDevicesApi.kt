package com.tradingplatform.app.data.api

import com.tradingplatform.app.data.model.VpnPeerListResponseDto
import retrofit2.Response
import retrofit2.http.GET

interface MyDevicesApi {
    @GET("v1/edge-control/me/vpn-peers")
    suspend fun getMyPeers(): Response<VpnPeerListResponseDto>
}
