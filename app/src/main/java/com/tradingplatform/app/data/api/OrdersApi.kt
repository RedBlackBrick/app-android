package com.tradingplatform.app.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query
import java.math.BigDecimal

/**
 * Subset of the backend ``OrderResponse`` schema that the mobile app actually
 * displays. Fields like ``signal_id``, ``metadata`` are intentionally
 * dropped — they are useful only on the web admin.
 */
@JsonClass(generateAdapter = true)
data class OrderDto(
    @Json(name = "id") val id: Long,
    @Json(name = "symbol") val symbol: String,
    @Json(name = "side") val side: String,
    @Json(name = "quantity") val quantity: BigDecimal? = null,
    @Json(name = "order_type") val orderType: String,
    @Json(name = "status") val status: String? = null,
    @Json(name = "filled_quantity") val filledQuantity: BigDecimal = BigDecimal.ZERO,
    @Json(name = "average_fill_price") val averageFillPrice: BigDecimal? = null,
    @Json(name = "limit_price") val limitPrice: BigDecimal? = null,
    @Json(name = "stop_price") val stopPrice: BigDecimal? = null,
    @Json(name = "portfolio_id") val portfolioId: String = "",
    @Json(name = "broker_order_id") val brokerOrderId: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null,
)

@JsonClass(generateAdapter = true)
data class ActiveOrdersResponseDto(
    @Json(name = "orders") val orders: List<OrderDto> = emptyList(),
    @Json(name = "count") val count: Int = 0,
)

@JsonClass(generateAdapter = true)
data class OrderHistoryResponseDto(
    @Json(name = "orders") val orders: List<OrderDto> = emptyList(),
    @Json(name = "count") val count: Int = 0,
)

interface OrdersApi {
    @GET("v1/orders/active")
    suspend fun listActiveOrders(
        @Query("portfolio_id") portfolioId: String,
    ): Response<ActiveOrdersResponseDto>

    @GET("v1/orders/history")
    suspend fun listOrderHistory(
        @Query("portfolio_id") portfolioId: String,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
    ): Response<OrderHistoryResponseDto>
}
