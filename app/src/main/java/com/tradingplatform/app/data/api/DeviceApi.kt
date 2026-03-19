package com.tradingplatform.app.data.api

import com.tradingplatform.app.data.model.DeviceListResponseDto
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

@JsonClass(generateAdapter = true)
data class DeviceCommandRequestDto(
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "command_type") val commandType: String,
)

interface DeviceApi {
    @GET("v1/edge/devices")
    suspend fun getDevices(): Response<DeviceListResponseDto>

    @DELETE("v1/edge/devices/{deviceId}")
    suspend fun unpairDevice(@Path("deviceId") deviceId: String): Response<Unit>

    @POST("v1/edge-control/commands")
    suspend fun sendCommand(
        @Body body: DeviceCommandRequestDto,
    ): Response<Unit>
}
