package com.tradingplatform.app.domain.model

enum class PnlPeriod {
    DAY, WEEK, MONTH, YEAR, ALL;

    fun toApiString(): String = name.lowercase()
}
