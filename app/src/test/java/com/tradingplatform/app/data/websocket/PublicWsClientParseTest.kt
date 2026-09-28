package com.tradingplatform.app.data.websocket

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Audit finding #E (audit/plan-market-data.md §E) — `market_data` payloads on the public
 * WS channel can carry `volume` as a decimal string (e.g. "123456.0", per MarketDataBridge).
 * The former `data.optString("volume", "0").toLongOrNull() ?: 0L` returned `null` for any
 * such shape (Kotlin's `String.toLongOrNull()` rejects a decimal point) and silently
 * defaulted to 0 — undercounting volume on every tick that carried a fractional string.
 *
 * Exercises [PublicWsClient.Companion.parseMarketData] directly (`internal`, pure — no
 * Android/Robolectric needed) rather than going through a full [PublicWsClient] instance,
 * whose `init` block has Android Lifecycle dependencies unrelated to parsing.
 */
class PublicWsClientParseTest {

    private fun marketDataJson(volume: Any?): JSONObject {
        val json = JSONObject()
        json.put("symbol", "AAPL")
        json.put("price", "185.50")
        json.put("open", "184.00")
        json.put("high", "186.00")
        json.put("low", "183.50")
        json.put("close", "185.50")
        json.put("volume", volume ?: JSONObject.NULL)
        json.put("bid", JSONObject.NULL)
        json.put("ask", JSONObject.NULL)
        return json
    }

    @Test
    fun `parses decimal-string volume by truncating to Long`() {
        val event = PublicWsClient.parseMarketData(marketDataJson("123456.0"), "2026-09-28T10:00:00Z")

        assertNotNull(event)
        assertEquals(123456L, event!!.volume)
    }

    @Test
    fun `parses plain integer-string volume`() {
        val event = PublicWsClient.parseMarketData(marketDataJson("654321"), "2026-09-28T10:00:00Z")

        assertNotNull(event)
        assertEquals(654321L, event!!.volume)
    }

    @Test
    fun `defaults to zero when volume is missing`() {
        val json = marketDataJson(null)
        json.remove("volume")

        val event = PublicWsClient.parseMarketData(json, "2026-09-28T10:00:00Z")

        assertNotNull(event)
        assertEquals(0L, event!!.volume)
    }

    @Test
    fun `defaults to zero when volume is not a number`() {
        val event = PublicWsClient.parseMarketData(marketDataJson("n/a"), "2026-09-28T10:00:00Z")

        assertNotNull(event)
        assertEquals(0L, event!!.volume)
    }
}
