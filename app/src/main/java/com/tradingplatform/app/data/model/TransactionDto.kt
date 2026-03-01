package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.math.BigDecimal

@JsonClass(generateAdapter = true)
data class TransactionDto(
    @Json(name = "id") val id: Long,
    @Json(name = "symbol") val symbol: String,
    @Json(name = "action") val action: String,
    @Json(name = "quantity") val quantity: BigDecimal,
    @Json(name = "price") val price: BigDecimal,
    @Json(name = "commission") val commission: BigDecimal,
    @Json(name = "total") val total: BigDecimal,
    @Json(name = "executed_at") val executedAt: String,
)
