package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.StrategiesApi
import com.tradingplatform.app.data.api.StrategiesWriteApi
import com.tradingplatform.app.di.NetworkModule
import com.tradingplatform.app.domain.exception.HttpStatusException
import com.tradingplatform.app.domain.model.PortfolioStrategyEntry
import com.tradingplatform.app.domain.model.PortfolioStrategyLink
import com.tradingplatform.app.domain.model.WriteOutcome
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Stratégies d'un portefeuille : lecture (liens + noms du catalogue paginé) et écriture
 * (pause / reprise d'un lien, `PATCH {"is_active": …}`), exercées avec les vraies interfaces
 * Retrofit contre un MockWebServer. Le client OkHttp de test reprend le réglage du client
 * `@Named("write")` : `retryOnConnectionFailure(false)`.
 */
class StrategiesRepositoryImplTest {

    private val server = MockWebServer()
    private val catalogRequests = CopyOnWriteArrayList<HttpUrl>()

    // Forme réelle d'un lien (contrat backend §4.1) : champs inutiles à l'app inclus pour vérifier
    // que le décodage les ignore.
    private fun linkJson(strategyId: String, isActive: Boolean) = """
        {
          "id": 412,
          "portfolio_id": "p1",
          "strategy_id": "$strategyId",
          "allocation_pct": 60.0,
          "budget_allocated": null,
          "alloc_mode": "percent",
          "is_active": $isActive,
          "max_positions_override": null,
          "custom_parameters": null,
          "strategy_version_number": null,
          "latest_version_number": 3,
          "version_is_active": null,
          "warnings": []
        }
    """.trimIndent()

    private val threeLinksJson = "[${linkJson("s1", true)},${linkJson("s2", false)},${linkJson("s3", true)}]"

    // Forme réelle d'un élément du catalogue (contrat backend §4.2), champs inutiles inclus.
    private fun catalogItemJson(id: String, name: String?): String {
        val nameJson = if (name == null) "null" else "\"$name\""
        return """
            {"id":"$id","user_id":12,"name":$nameJson,"strategy_code":"MOM_US","strategy_type":"standard",
             "status":"ACTIVE","symbols":["AAPL","MSFT"],"is_active":true,"is_public":false,
             "owner_first_name":null,"owner_email":null,"total_return_pct":null,"win_rate":null}
        """.trimIndent().replace("\n", "")
    }

    private fun catalogPage(items: List<Pair<String, String?>>, total: Int, offset: Int, limit: Int): MockResponse {
        val itemsJson = items.joinToString(",") { catalogItemJson(it.first, it.second) }
        return MockResponse().setResponseCode(200).setBody(
            "{\"items\":[$itemsJson],\"total\":$total,\"offset\":$offset,\"limit\":$limit}",
        )
    }

    private fun json200(body: String) = MockResponse().setResponseCode(200).setBody(body)

    /** Dispatcher lecture : liens du portefeuille `p1` + catalogue calculé par [catalog]. */
    private fun useDispatcher(
        links: MockResponse = json200(threeLinksJson),
        catalog: (offset: Int, limit: Int) -> MockResponse = { _, _ -> MockResponse().setResponseCode(404) },
    ) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
                return when (url.encodedPath) {
                    "/v1/portfolios/p1/strategies" -> links
                    "/v1/strategies" -> {
                        catalogRequests += url
                        catalog(
                            url.queryParameter("offset")!!.toInt(),
                            url.queryParameter("limit")!!.toInt(),
                        )
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun repository(readTimeoutMs: Long = 10_000L): StrategiesRepositoryImpl {
        val client = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(NetworkModule.provideMoshi()))
            .build()
        return StrategiesRepositoryImpl(
            retrofit.create(StrategiesApi::class.java),
            retrofit.create(StrategiesWriteApi::class.java),
        )
    }

    // ── Lecture : liens + noms ──────────────────────────────────────────────────────────────

    @Test
    fun `entries join links with catalog names and keep the link activity`() = runTest {
        // s3 n'est pas dans le catalogue (supprimée ou privée d'un autre utilisateur).
        useDispatcher(
            catalog = { offset, limit ->
                catalogPage(listOf("s1" to "Momentum US", "s2" to "Mean Reversion EU"), 2, offset, limit)
            },
        )

        val result = repository().listPortfolioStrategyEntries("p1")

        assertEquals(
            listOf(
                PortfolioStrategyEntry(strategyId = "s1", name = "Momentum US", isActive = true),
                PortfolioStrategyEntry(strategyId = "s2", name = "Mean Reversion EU", isActive = false),
                PortfolioStrategyEntry(strategyId = "s3", name = null, isActive = true),
            ),
            result.getOrNull(),
        )
        // Le catalogue est demandé avec le limit maximal (200) et une seule page suffisait.
        assertEquals(1, catalogRequests.size)
        assertEquals("200", catalogRequests[0].queryParameter("limit"))
        assertEquals("0", catalogRequests[0].queryParameter("offset"))
    }

    @Test
    fun `entries merge catalog pages until every linked strategy is named`() = runTest {
        // Le serveur répond par pages de 2 quel que soit le limit demandé.
        useDispatcher(
            catalog = { offset, limit ->
                when (offset) {
                    0 -> catalogPage(listOf("x1" to "Autre", "s1" to "Momentum US"), 3, offset, limit)
                    else -> catalogPage(listOf("s2" to "Mean Reversion EU"), 3, offset, limit)
                }
            },
            links = json200("[${linkJson("s1", true)},${linkJson("s2", false)}]"),
        )

        val result = repository().listPortfolioStrategyEntries("p1")

        assertEquals(
            listOf(
                PortfolioStrategyEntry(strategyId = "s1", name = "Momentum US", isActive = true),
                PortfolioStrategyEntry(strategyId = "s2", name = "Mean Reversion EU", isActive = false),
            ),
            result.getOrNull(),
        )
        assertEquals(listOf("0", "2"), catalogRequests.map { it.queryParameter("offset") })
        assertEquals(listOf("200", "200"), catalogRequests.map { it.queryParameter("limit") })
    }

    @Test
    fun `entries walk every page when a linked strategy is never found`() = runTest {
        useDispatcher(
            catalog = { offset, limit ->
                when (offset) {
                    0 -> catalogPage(listOf("s1" to "Momentum US", "s2" to "Mean Reversion EU"), 3, offset, limit)
                    else -> catalogPage(listOf("z9" to "Inconnue des liens"), 3, offset, limit)
                }
            },
        )

        val result = repository().listPortfolioStrategyEntries("p1")

        assertEquals(listOf("Momentum US", "Mean Reversion EU", null), result.getOrNull()!!.map { it.name })
        // total = 3 atteint après la 2e page : le parcours s'arrête sans 3e requête.
        assertEquals(listOf("0", "2"), catalogRequests.map { it.queryParameter("offset") })
    }

    @Test
    fun `entries stop paging as soon as every linked strategy has a name`() = runTest {
        val page = listOf("s1" to "Momentum US", "s2" to "Mean Reversion EU", "s3" to "Value") +
            (1..197).map { "f$it" to "Filler $it" }
        useDispatcher(catalog = { offset, limit -> catalogPage(page, 1000, offset, limit) })

        val result = repository().listPortfolioStrategyEntries("p1")

        assertEquals(listOf("Momentum US", "Mean Reversion EU", "Value"), result.getOrNull()!!.map { it.name })
        assertEquals(1, catalogRequests.size)
    }

    @Test
    fun `catalog scan is bounded to 500 strategies`() = runTest {
        // Catalogue « infini » qui ne contient jamais s1..s3.
        useDispatcher(
            catalog = { offset, limit ->
                catalogPage((offset until offset + limit).map { "x$it" to "Strategy $it" }, 10_000, offset, limit)
            },
        )

        val result = repository().listPortfolioStrategyEntries("p1")

        assertTrue(result.isSuccess)
        assertEquals(listOf(null, null, null), result.getOrNull()!!.map { it.name })
        assertEquals(listOf("0", "200", "400"), catalogRequests.map { it.queryParameter("offset") })
        assertEquals(listOf("200", "200", "100"), catalogRequests.map { it.queryParameter("limit") })
    }

    @Test
    fun `blank or null catalog names give a null name`() = runTest {
        useDispatcher(
            links = json200("[${linkJson("s1", true)},${linkJson("s2", true)}]"),
            catalog = { offset, limit -> catalogPage(listOf("s1" to "   ", "s2" to null), 2, offset, limit) },
        )

        val result = repository().listPortfolioStrategyEntries("p1")

        assertEquals(listOf(null, null), result.getOrNull()!!.map { it.name })
    }

    @Test
    fun `catalog failure degrades to null names without failing the result`() = runTest {
        useDispatcher(catalog = { _, _ -> MockResponse().setResponseCode(500) })

        val result = repository().listPortfolioStrategyEntries("p1")

        assertEquals(
            listOf(
                PortfolioStrategyEntry(strategyId = "s1", name = null, isActive = true),
                PortfolioStrategyEntry(strategyId = "s2", name = null, isActive = false),
                PortfolioStrategyEntry(strategyId = "s3", name = null, isActive = true),
            ),
            result.getOrNull(),
        )
    }

    @Test
    fun `links failure fails the result and never queries the catalog`() = runTest {
        useDispatcher(links = MockResponse().setResponseCode(500))

        val result = repository().listPortfolioStrategyEntries("p1")

        assertTrue(result.isFailure)
        assertEquals(0, catalogRequests.size)
    }

    @Test
    fun `no links gives an empty list without querying the catalog`() = runTest {
        useDispatcher(links = json200("[]"))

        val result = repository().listPortfolioStrategyEntries("p1")

        assertEquals(emptyList<PortfolioStrategyEntry>(), result.getOrNull())
        assertEquals(0, catalogRequests.size)
    }

    @Test
    fun `listPortfolioStrategies still returns the raw links`() = runTest {
        useDispatcher()

        val result = repository().listPortfolioStrategies("p1")

        assertEquals(
            listOf(
                PortfolioStrategyLink(portfolioId = "p1", strategyId = "s1", isActive = true),
                PortfolioStrategyLink(portfolioId = "p1", strategyId = "s2", isActive = false),
                PortfolioStrategyLink(portfolioId = "p1", strategyId = "s3", isActive = true),
            ),
            result.getOrNull(),
        )
        assertEquals(0, catalogRequests.size)
    }

    // ── Écriture : pause / reprise ─────────────────────────────────────────────────────────

    @Test
    fun `pause sends a single PATCH with exactly the is_active false body`() = runTest {
        server.enqueue(json200(linkJson("s1", false)))

        val result = repository().setPortfolioStrategyActive("p1", "s1", active = false)

        assertEquals(WriteOutcome.CONFIRMED, result.getOrNull())
        assertEquals(1, server.requestCount)
        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("PATCH", request!!.method)
        assertEquals("/v1/portfolios/p1/strategies/s1", request.path)
        assertEquals("""{"is_active":false}""", request.body.readUtf8())
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
    }

    @Test
    fun `resume sends exactly the is_active true body`() = runTest {
        server.enqueue(json200(linkJson("s2", true)))

        val result = repository().setPortfolioStrategyActive("p1", "s2", active = true)

        assertEquals(WriteOutcome.CONFIRMED, result.getOrNull())
        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("/v1/portfolios/p1/strategies/s2", request!!.path)
        assertEquals("""{"is_active":true}""", request.body.readUtf8())
    }

    @Test
    fun `patch 200 with an unreadable body is still CONFIRMED`() = runTest {
        server.enqueue(json200("<html>not json</html>"))

        val result = repository().setPortfolioStrategyActive("p1", "s1", active = false)

        assertEquals(WriteOutcome.CONFIRMED, result.getOrNull())
    }

    @Test
    fun `patch 409 fails with an explicit conflict message and no replay`() = runTest {
        server.enqueue(MockResponse().setResponseCode(409))

        val result = repository().setPortfolioStrategyActive("p1", "s1", active = false)

        val failure = result.exceptionOrNull()
        assertTrue("expected HttpStatusException, got $failure", failure is HttpStatusException)
        assertEquals(409, (failure as HttpStatusException).code)
        assertEquals(
            "Conflit : l'état de cette stratégie a changé, actualisez avant de réessayer",
            failure.message,
        )
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `patch 500 and 503 are REQUESTED_UNCONFIRMED and sent only once each`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(503))
        val repository = repository()

        val first = repository.setPortfolioStrategyActive("p1", "s1", active = false)
        val second = repository.setPortfolioStrategyActive("p1", "s1", active = false)

        assertEquals(WriteOutcome.REQUESTED_UNCONFIRMED, first.getOrNull())
        assertEquals(WriteOutcome.REQUESTED_UNCONFIRMED, second.getOrNull())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `patch 400 401 403 404 and 422 are failures carrying the HTTP code`() = runTest {
        val codes = listOf(400, 401, 403, 404, 422)
        codes.forEach { code -> server.enqueue(MockResponse().setResponseCode(code)) }
        val repository = repository()

        codes.forEach { code ->
            val failure = repository.setPortfolioStrategyActive("p1", "s1", active = true).exceptionOrNull()
            assertTrue("HTTP $code should be a failure, got $failure", failure is HttpStatusException)
            assertEquals(code, (failure as HttpStatusException).code)
        }
        assertEquals(codes.size, server.requestCount)
    }

    @Test
    fun `patch connection dropped after send is REQUESTED_UNCONFIRMED without replay`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        val result = repository().setPortfolioStrategyActive("p1", "s1", active = false)

        assertEquals(WriteOutcome.REQUESTED_UNCONFIRMED, result.getOrNull())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `patch read timeout after send is REQUESTED_UNCONFIRMED without replay`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val result = repository(readTimeoutMs = 300L).setPortfolioStrategyActive("p1", "s1", active = false)

        assertEquals(WriteOutcome.REQUESTED_UNCONFIRMED, result.getOrNull())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `blank ids fail without sending anything`() = runTest {
        val repository = repository()

        val blankPortfolio = repository.setPortfolioStrategyActive("", "s1", active = false)
        val blankStrategy = repository.setPortfolioStrategyActive("p1", " ", active = false)

        assertTrue(blankPortfolio.exceptionOrNull() is IllegalArgumentException)
        assertTrue(blankStrategy.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, server.requestCount)
    }
}
