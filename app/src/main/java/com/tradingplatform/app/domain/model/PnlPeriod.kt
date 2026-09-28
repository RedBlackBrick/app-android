package com.tradingplatform.app.domain.model

enum class PnlPeriod(private val apiValue: String) {
    DAY("day"),
    WEEK("week"),
    MONTH("month"),

    /** Year-to-date — le backend `/pnl` accepte `ytd` (pas `year`). */
    YEAR("ytd"),
    ALL("all");

    fun toApiString(): String = apiValue

    companion object {
        /** Inverse de [toApiString] — null si la valeur est inconnue. */
        fun fromApiString(value: String): PnlPeriod? = entries.firstOrNull { it.apiValue == value }
    }
}
