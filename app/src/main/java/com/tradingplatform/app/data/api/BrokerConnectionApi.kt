package com.tradingplatform.app.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * PR-5c FINDING / PR-2.5 fix: `execution_mode` was never a field of
 * `DeviceBrokerConnectionResponse` (trading-platform2 app/edge/schemas.py:769+, returned by
 * `GET /v1/edge/broker-connections/{device_id}` in broker_relay_router.py). It was
 * confused with the unrelated `broker_execution_mode` field of `BrokerPermissionsUpdate`
 * (app/edge/schemas.py — an admin request body, different endpoint entirely) and with
 * `BrokerSummary.execution_mode` (a distinct, legitimately-sent schema used by
 * [BrokerSummaryDto], not this one). Removed here; never provided, so dropped from the
 * domain [com.tradingplatform.app.domain.model.BrokerConnection] and its UI consumer too.
 */
@JsonClass(generateAdapter = true)
data class BrokerConnectionDto(
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "portfolio_id") val portfolioId: String? = null,
    @Json(name = "broker_code") val brokerCode: String,
    @Json(name = "connection_status") val connectionStatus: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
)

interface BrokerConnectionApi {
    @GET("v1/edge/broker-connections/{device_id}")
    suspend fun getBrokerConnections(
        @Path("device_id") deviceId: String,
    ): Response<List<BrokerConnectionDto>>
}
