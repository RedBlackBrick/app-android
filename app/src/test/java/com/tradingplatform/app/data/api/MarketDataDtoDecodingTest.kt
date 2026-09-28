package com.tradingplatform.app.data.api

import com.squareup.moshi.Types
import com.tradingplatform.app.data.local.db.dao.QuoteDao
import com.tradingplatform.app.data.model.MarketDataPointDto
import com.tradingplatform.app.data.repository.MarketDataRepositoryImpl
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Audit findings #6 (E-contract-1) and #7 (E-contract-2) — market-data wire contracts.
 *
 * Fixtures under `src/test/resources/fixtures/backend/` are copied from the backend
 * pydantic schemas (trading-platform2/app/market_data/schemas.py):
 * - `symbols_list.json`   = `SymbolListResponse` {symbols:[SymbolListItem], total, limit, offset, has_more}
 * - `market_data_1d.json` = `MarketDataResponse` {symbol, data:[MarketDataPoint], count, timeframe, next_cursor}
 *   (pydantic v2 serializes Decimal as JSON strings, datetimes as ISO-8601 "...Z").
 *
 * Two layers of tests:
 * - direct Moshi decoding into the type the current Retrofit interface declares — RED today
 *   (BEGIN_OBJECT vs BEGIN_ARRAY); these must be re-pointed at the new DTOs by the fix;
 * - repository-level tests (Retrofit + MockWebServer serving the fixture) that go through
 *   the domain API (`Result<List<String>>` / `Result<List<BigDecimal>>`). Their signatures
 *   survive the fix unchanged, so they are the durable regression guard. RED today.
 */
class MarketDataDtoDecodingTest {

    private val moshi = NetworkModule.provideMoshi()

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("fixtures/backend/$name")) {
            "missing fixture fixtures/backend/$name"
        }.readText()

    // ── Direct decoding (to re-point at the new DTOs) ─────────────────────────

    @Test
    fun `symbols list fixture decodes into the type MarketDataApi getSymbols declares`() {
        // TODO(audit #6): after the fix, decode into SymbolListResponseDto and assert
        //  symbols.map { it.ticker } == listOf("AAPL", "MSFT", "MC.PA", "BTC-USD").
        val adapter = moshi.adapter<List<String>>(
            Types.newParameterizedType(List::class.java, String::class.java)
        )

        val decoded = runCatching { adapter.fromJson(fixture("symbols_list.json")) }

        assertTrue(
            "GET /v1/market-data/symbols body does not decode as List<String>: ${decoded.exceptionOrNull()}",
            decoded.isSuccess,
        )
    }

    @Test
    fun `history fixture decodes into the type MarketDataApi getHistory declares`() {
        // TODO(audit #7): after the fix, decode into MarketDataResponseDto and assert
        //  data.map { it.close } == [228.9200, 230.4100, 231.8800].
        val adapter = moshi.adapter<List<MarketDataPointDto>>(
            Types.newParameterizedType(List::class.java, MarketDataPointDto::class.java)
        )

        val decoded = runCatching { adapter.fromJson(fixture("market_data_1d.json")) }

        assertTrue(
            "GET /v1/market-data/{symbol}/history body does not decode as List<MarketDataPointDto>: " +
                "${decoded.exceptionOrNull()}",
            decoded.isSuccess,
        )
    }

    // ── Repository level (durable across the fix) ─────────────────────────────

    private val server = MockWebServer()
    private val historyRequests = CopyOnWriteArrayList<RecordedRequest>()
    private lateinit var repository: MarketDataRepositoryImpl

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
                return when {
                    url.encodedPath == "/v1/market-data/symbols" ->
                        MockResponse().setResponseCode(200).setBody(fixture("symbols_list.json"))

                    url.encodedPath.matches(Regex("/v1/market-data/[^/]+/history")) -> {
                        historyRequests += request
                        // Mirror the backend: start and end are required Query(...) params.
                        if (url.queryParameter("start") == null || url.queryParameter("end") == null) {
                            MockResponse().setResponseCode(422).setBody(
                                """{"detail":[{"type":"missing","loc":["query","start"],"msg":"Field required"}]}"""
                            )
                        } else {
                            MockResponse().setResponseCode(200).setBody(fixture("market_data_1d.json"))
                        }
                    }

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()

        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(MarketDataApi::class.java)
        repository = MarketDataRepositoryImpl(api, mockk<QuoteDao>(relaxed = true))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getAvailableSymbols returns the tickers of the paginated backend response`() = runTest {
        val result = repository.getAvailableSymbols()

        assertTrue("getAvailableSymbols failed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(listOf("AAPL", "MSFT", "MC.PA", "BTC-USD"), result.getOrNull())
    }

    @Test
    fun `getHistory sends start end timeframe and returns the close series`() = runTest {
        val result = repository.getHistory("aapl", 30)

        val request = historyRequests.lastOrNull()
        assertNotNull("no request reached /v1/market-data/{symbol}/history", request)
        val url = request!!.requestUrl!!
        assertEquals("/v1/market-data/AAPL/history", url.encodedPath)
        assertNotNull("missing required query param start", url.queryParameter("start"))
        assertNotNull("missing required query param end", url.queryParameter("end"))
        assertEquals("1d", url.queryParameter("timeframe"))

        assertTrue("getHistory failed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(
            listOf(BigDecimal("228.9200"), BigDecimal("230.4100"), BigDecimal("231.8800")),
            result.getOrNull(),
        )
    }
}
