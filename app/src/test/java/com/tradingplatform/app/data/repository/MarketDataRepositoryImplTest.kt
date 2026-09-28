package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.MarketDataApi
import com.tradingplatform.app.data.local.db.dao.QuoteDao
import com.tradingplatform.app.di.NetworkModule
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.math.BigDecimal

/**
 * PR 2.1 (audit #6, #7) — server-side symbol search/pagination and sparkline history
 * ordering, exercised through the real Retrofit interface against a MockWebServer.
 * Complements [com.tradingplatform.app.data.api.MarketDataDtoDecodingTest], which pins the
 * wire-format decoding for both endpoints against fixtures copied from the backend schemas.
 */
class MarketDataRepositoryImplTest {

    private val server = MockWebServer()
    private lateinit var repository: MarketDataRepositoryImpl
    private var lastSymbolsRequest: RecordedRequest? = null

    // One inactive symbol mixed in — repository.getAvailableSymbols() must filter it out.
    private val symbolsWithInactiveJson = """
        {
          "symbols": [
            {"sid": 1, "ticker": "AAPL", "name": "Apple Inc.", "exchange": "NASDAQ", "currency": "USD", "is_active": true},
            {"sid": 5, "ticker": "DELISTED", "name": "Delisted Co", "exchange": "NYSE", "currency": "USD", "is_active": false},
            {"sid": 2, "ticker": "MSFT", "name": "Microsoft Corporation", "exchange": "NASDAQ", "currency": "USD", "is_active": true}
          ],
          "total": 3,
          "limit": 2,
          "offset": 0,
          "has_more": true
        }
    """.trimIndent()

    // Backend get_range (/{symbol}/history) returns ascending (oldest first) — unlike
    // get_latest (GET /v1/market-data/), which is DESC.
    private val historyJson = """
        {
          "symbol": "AAPL",
          "data": [
            {"timestamp": "2026-09-23T00:00:00Z", "open": "226.10", "high": "229.45", "low": "225.80", "close": "228.9200", "volume": 51234567},
            {"timestamp": "2026-09-24T00:00:00Z", "open": "229.00", "high": "231.20", "low": "228.15", "close": "230.4100", "volume": 47654321},
            {"timestamp": "2026-09-25T00:00:00Z", "open": "230.50", "high": "232.00", "low": "229.70", "close": "231.8800", "volume": null}
          ],
          "count": 3,
          "timeframe": "1d",
          "next_cursor": null
        }
    """.trimIndent()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
                return when {
                    url.encodedPath == "/v1/market-data/symbols" -> {
                        lastSymbolsRequest = request
                        MockResponse().setResponseCode(200).setBody(symbolsWithInactiveJson)
                    }

                    url.encodedPath.matches(Regex("/v1/market-data/[^/]+/history")) ->
                        MockResponse().setResponseCode(200).setBody(historyJson)

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()

        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(MoshiConverterFactory.create(NetworkModule.provideMoshi()))
            .build()
            .create(MarketDataApi::class.java)
        repository = MarketDataRepositoryImpl(api, mockk<QuoteDao>(relaxed = true))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getAvailableSymbols with pagination filters out inactive symbols`() = runTest {
        val result = repository.getAvailableSymbols(search = "A", limit = 2, offset = 0)

        assertTrue("getAvailableSymbols failed: ${result.exceptionOrNull()}", result.isSuccess)
        val page = result.getOrNull()!!
        assertEquals(listOf("AAPL", "MSFT"), page.items.map { it.ticker })
        assertTrue(page.hasMore)
        // offset (0) + raw item count (3, before filtering) — matches the server pagination cursor.
        assertEquals(3, page.nextOffset)

        val request = lastSymbolsRequest!!.requestUrl!!
        assertEquals("A", request.queryParameter("search"))
        assertEquals("2", request.queryParameter("limit"))
        assertEquals("0", request.queryParameter("offset"))
    }

    @Test
    fun `getAvailableSymbols no-arg overload also filters inactive symbols`() = runTest {
        val result = repository.getAvailableSymbols()

        assertTrue("getAvailableSymbols failed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(listOf("AAPL", "MSFT"), result.getOrNull())
    }

    @Test
    fun `getHistory returns close series in the backend's chronological order`() = runTest {
        val result = repository.getHistory("aapl", 30)

        assertTrue("getHistory failed: ${result.exceptionOrNull()}", result.isSuccess)
        // No reversal applied — get_range (/{symbol}/history) is already ascending. Had this
        // gone through get_latest (GET /v1/market-data/, DESC) instead, this would need
        // .asReversed() to match the same expected order.
        assertEquals(
            listOf(BigDecimal("228.9200"), BigDecimal("230.4100"), BigDecimal("231.8800")),
            result.getOrNull(),
        )
        assertFalse(result.getOrNull().isNullOrEmpty())
    }
}
