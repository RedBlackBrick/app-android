package com.tradingplatform.app.data.model

import com.squareup.moshi.FromJson
import com.squareup.moshi.ToJson
import java.math.BigDecimal

class BigDecimalAdapter {
    @FromJson
    fun fromJson(value: String): BigDecimal = BigDecimal(value)

    @ToJson
    fun toJson(value: BigDecimal): String = value.toPlainString()
}
