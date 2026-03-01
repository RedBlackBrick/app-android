package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PortfolioListResponseDto(
    @Json(name = "portfolios") val portfolios: List<PortfolioDto>,
)
