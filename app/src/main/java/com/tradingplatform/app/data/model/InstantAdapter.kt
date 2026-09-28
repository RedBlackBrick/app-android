package com.tradingplatform.app.data.model

import com.squareup.moshi.FromJson
import com.squareup.moshi.ToJson
import com.tradingplatform.app.domain.util.parseInstantLenient
import java.time.Instant

class InstantAdapter {
    @FromJson
    fun fromJson(value: String): Instant = parseInstantLenient(value)

    @ToJson
    fun toJson(value: Instant): String = value.toString()
}
