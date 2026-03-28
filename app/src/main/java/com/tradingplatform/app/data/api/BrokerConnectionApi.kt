package com.tradingplatform.app.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

@JsonClass(generateAdapter = true)
data class BrokerConnectionDto(
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "portfolio_id") val portfolioId: String? = null,
    @Json(name = "broker_code") val brokerCode: String,
    @Json(name = "connection_status") val connectionStatus: String? = null,
    @Json(name = "execution_mode") val executionMode: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
)

@JsonClass(generateAdapter = true)
data class DeployBrokerRequestDto(
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "broker_code") val brokerCode: String,
    @Json(name = "portfolio_id") val portfolioId: String? = null,
)

@JsonClass(generateAdapter = true)
data class BrokerTestResultDto(
    @Json(name = "healthy") val healthy: Boolean,
    @Json(name = "message") val message: String? = null,
)

interface BrokerConnectionApi {
    @GET("v1/edge/broker-connections/{device_id}")
    suspend fun getBrokerConnections(
        @Path("device_id") deviceId: String,
    ): Response<List<BrokerConnectionDto>>

    @POST("v1/edge/broker-connections")
    suspend fun deployBroker(
        @Body request: DeployBrokerRequestDto,
    ): Response<BrokerConnectionDto>

    @DELETE("v1/edge/broker-connections/{device_id}/{portfolio_id}")
    suspend fun removeBroker(
        @Path("device_id") deviceId: String,
        @Path("portfolio_id") portfolioId: String,
    ): Response<Unit>

    @POST("v1/edge/broker-connections/{device_id}/test")
    suspend fun testBrokerConnection(
        @Path("device_id") deviceId: String,
    ): Response<BrokerTestResultDto>
}
