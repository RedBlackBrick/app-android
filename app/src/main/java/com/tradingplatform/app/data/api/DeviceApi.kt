package com.tradingplatform.app.data.api

import com.tradingplatform.app.data.model.DeviceListResponseDto
import retrofit2.Response
import retrofit2.http.GET

interface DeviceApi {
    @GET("v1/edge/devices")
    suspend fun getDevices(): Response<DeviceListResponseDto>
}
