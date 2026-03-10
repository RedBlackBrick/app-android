package com.tradingplatform.app.data.api

import com.tradingplatform.app.data.model.DeviceListResponseDto
import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Path

interface DeviceApi {
    @GET("v1/edge/devices")
    suspend fun getDevices(): Response<DeviceListResponseDto>

    @DELETE("v1/edge/devices/{deviceId}")
    suspend fun unpairDevice(@Path("deviceId") deviceId: String): Response<Unit>
}
