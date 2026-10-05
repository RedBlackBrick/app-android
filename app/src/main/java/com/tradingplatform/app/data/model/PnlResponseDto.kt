package com.tradingplatform.app.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.math.BigDecimal

/**
 * `GET /v1/portfolios/{id}/pnl`. Les montants et le pourcentage sont NULLABLES (audit F9 P-02/P-03) :
 * pour `period != all` le backend ne sait pas scinder réalisé/latent par période (`null`), et
 * `total_pnl_percent` est `null` quand le capital contribué est nul. Un `null` n'est pas un 0.
 */
@JsonClass(generateAdapter = true)
data class PnlResponseDto(
    @Json(name = "period") val period: String,
    @Json(name = "realized_pnl") val realizedPnl: BigDecimal? = null,
    @Json(name = "unrealized_pnl") val unrealizedPnl: BigDecimal? = null,
    @Json(name = "total_pnl") val totalPnl: BigDecimal? = null,
    @Json(name = "total_pnl_percent") val totalPnlPercent: Double? = null,
    @Json(name = "trades_count") val tradesCount: Int,
    @Json(name = "winning_trades") val winningTrades: Int,
    @Json(name = "losing_trades") val losingTrades: Int,
)
