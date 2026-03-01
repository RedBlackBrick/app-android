package com.tradingplatform.app.data.model

import com.squareup.moshi.FromJson
import com.squareup.moshi.ToJson
import java.time.Instant

class InstantAdapter {
    @FromJson
    fun fromJson(value: String): Instant = Instant.parse(value)

    @ToJson
    fun toJson(value: Instant): String = value.toString()
}
