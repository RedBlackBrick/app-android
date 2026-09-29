package com.tradingplatform.app.data.repository

import com.squareup.moshi.Moshi
import com.tradingplatform.app.data.api.RiskApi
import com.tradingplatform.app.data.api.RiskWriteApi
import com.tradingplatform.app.data.model.BigDecimalAdapter
import com.tradingplatform.app.data.model.InstantAdapter
import com.tradingplatform.app.domain.exception.HttpStatusException
import com.tradingplatform.app.domain.model.WriteOutcome
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * `getRiskStatus` (agrégat tolérant de 3 lectures) et `activatePortfolioKillSwitch` (écriture
 * jamais rejouée, activation seule) via les vraies interfaces Retrofit contre un MockWebServer.
 * Le client d'écriture imite `@Named("write")` : `retryOnConnectionFailure(false)`.
 */
class RiskRepositoryImplTest {

    private val server = MockWebServer()
    private lateinit var repository: RiskRepositoryImpl

    /** "METHOD /path" -> réponse. Les 3 lectures partent en parallèle : dispatch par chemin, pas par ordre. */
    private val responses = ConcurrentHashMap<String, MockResponse>()
    private val seenRequests = java.util.Collections.synchronizedList(mutableListOf<RecordedRequest>())

    private val killSwitchPath = "GET /v1/risk/kill-switch/active"
    private val summaryPath = "GET /v1/risk/portfolios/p-1/risk-360-summary"
    private val violationsPath = "GET /v1/risk/violations"
    private val activatePath = "POST /v1/risk/portfolios/p-1/kill-switch"

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setBody(body)

    private val killSwitchActiveJson = """
        {"global_active": false, "portfolio_active": {"p-1": true, "p-2": false},
         "symbol_active": {"AAPL": false}, "any_active": true}
    """.trimIndent()

    private val summaryJson = """
        {
          "portfolio_id": "p-1",
          "portfolio_name": "Growth EUR",
          "current_value": "10074.0",
          "initial_capital": "10000.0",
          "var_95": 231.45,
          "var_95_pct": 0.023,
          "drawdown_current_pct": -12.4,
          "exposure_pct": 0.87,
          "daily_pnl": "-250.00",
          "daily_loss_limit": "500.0",
          "strategy_contribution": [],
          "kill_switches_active": [
            {"scope": "portfolio:p-1", "reason": "Drawdown limit breached",
             "activated_by": "system:drawdown_guard", "activated_at": "2026-09-29T07:41:00.000000Z"}
          ],
          "active_rules": [],
          "generated_at": "2026-09-29T09:05:00.123456Z"
        }
    """.trimIndent()

    private val violationsJson = """
        [
          {"id": 1, "portfolio_id": "p-1", "violation_type": "max_daily_loss", "severity": "critical", "is_resolved": false},
          {"id": 2, "portfolio_id": "p-1", "violation_type": "max_drawdown", "severity": "warning", "is_resolved": false},
          {"id": 3, "portfolio_id": "p-1", "violation_type": "max_drawdown", "severity": "info", "is_resolved": true}
        ]
    """.trimIndent()

    private fun stubHealthyReads() {
        responses[killSwitchPath] = json(200, killSwitchActiveJson)
        responses[summaryPath] = json(200, summaryJson)
        responses[violationsPath] = json(200, violationsJson)
    }

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seenRequests += request
                val key = "${request.method} ${request.requestUrl?.encodedPath}"
                return responses[key] ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val moshi = Moshi.Builder().add(BigDecimalAdapter()).add(InstantAdapter()).build()
        val readRetrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        val writeClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .readTimeout(300, TimeUnit.MILLISECONDS)
            .build()
        val writeRetrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(writeClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        repository = RiskRepositoryImpl(
            api = readRetrofit.create(RiskApi::class.java),
            writeApi = writeRetrofit.create(RiskWriteApi::class.java),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── getRiskStatus ──────────────────────────────────────────────────────

    @Test
    fun `getRiskStatus aggregates kill switch, violations, daily loss and drawdown`() = runTest {
        stubHealthyReads()

        val status = repository.getRiskStatus("p-1").getOrThrow()

        assertTrue(status.killSwitchActive)
        assertEquals("Drawdown limit breached", status.killSwitchReason)
        // 3 violations renvoyées dont 1 résolue : seules les 2 non résolues comptent.
        assertEquals(2, status.unresolvedViolations)
        // Perte du jour 250 / limite 500 = 50 % = fraction 0.5.
        assertEquals(0.5, status.dailyLossUsagePct!!, 1e-9)
        // Backend : -12.4 (pourcentage) -> domaine : -0.124 (fraction).
        assertEquals(-0.124, status.drawdownCurrentPct!!, 1e-9)
        assertFalse(status.isPartial)
    }

    @Test
    fun `getRiskStatus asks only for unresolved violations of the portfolio with the capped limit`() = runTest {
        stubHealthyReads()

        repository.getRiskStatus("p-1").getOrThrow()

        val violationsRequest = seenRequests.single { it.requestUrl?.encodedPath == "/v1/risk/violations" }
        assertEquals("p-1", violationsRequest.requestUrl?.queryParameter("portfolio_id"))
        assertEquals("false", violationsRequest.requestUrl?.queryParameter("is_resolved"))
        assertEquals("100", violationsRequest.requestUrl?.queryParameter("limit"))
        assertEquals(3, seenRequests.size)
    }

    @Test
    fun `getRiskStatus treats a global kill switch as active for the portfolio`() = runTest {
        stubHealthyReads()
        responses[killSwitchPath] = json(200, """{"global_active": true, "portfolio_active": {"p-1": false}}""")
        responses[summaryPath] = json(
            200,
            """{"kill_switches_active": [{"scope": "global", "reason": "Arrêt global", "activated_by": "1"}]}""",
        )

        val status = repository.getRiskStatus("p-1").getOrThrow()

        assertTrue(status.killSwitchActive)
        assertEquals("Arrêt global", status.killSwitchReason)
    }

    @Test
    fun `getRiskStatus ignores a strategy kill switch and reports no reason when nothing is active`() = runTest {
        stubHealthyReads()
        responses[killSwitchPath] = json(200, """{"global_active": false, "portfolio_active": {"p-1": false}}""")
        responses[summaryPath] = json(
            200,
            """{"kill_switches_active": [{"scope": "strategy:s-9", "reason": "Stratégie coupée", "activated_by": "1"}]}""",
        )

        val status = repository.getRiskStatus("p-1").getOrThrow()

        assertFalse(status.killSwitchActive)
        assertNull(status.killSwitchReason)
    }

    @Test
    fun `getRiskStatus degrades the risk-360 fields when that read fails`() = runTest {
        stubHealthyReads()
        responses[summaryPath] = json(500, """{"message":"boom"}""")

        val status = repository.getRiskStatus("p-1").getOrThrow()

        assertTrue(status.killSwitchActive)
        assertNull(status.killSwitchReason)
        assertNull(status.dailyLossUsagePct)
        assertNull(status.drawdownCurrentPct)
        assertEquals(2, status.unresolvedViolations)
        assertTrue(status.isPartial)
    }

    @Test
    fun `getRiskStatus derives the kill switch from risk-360 when kill-switch active read fails`() = runTest {
        stubHealthyReads()
        responses[killSwitchPath] = json(500, "{}")

        val status = repository.getRiskStatus("p-1").getOrThrow()

        assertTrue(status.killSwitchActive)
        assertEquals("Drawdown limit breached", status.killSwitchReason)
        assertTrue(status.isPartial)
    }

    @Test
    fun `getRiskStatus reports zero violations when that read fails`() = runTest {
        stubHealthyReads()
        responses[violationsPath] = json(500, "Internal Server Error")

        val status = repository.getRiskStatus("p-1").getOrThrow()

        assertEquals(0, status.unresolvedViolations)
        assertEquals(0.5, status.dailyLossUsagePct!!, 1e-9)
        assertTrue(status.isPartial)
    }

    @Test
    fun `getRiskStatus fails only when all three reads fail`() = runTest {
        responses[killSwitchPath] = json(500, "{}")
        responses[summaryPath] = json(500, "{}")
        responses[violationsPath] = json(500, "{}")

        val error = repository.getRiskStatus("p-1").exceptionOrNull()

        assertTrue(error is HttpStatusException)
        assertEquals(500, (error as HttpStatusException).code)
    }

    @Test
    fun `getRiskStatus daily loss usage is null without a configured limit`() = runTest {
        stubHealthyReads()
        responses[summaryPath] = json(200, """{"daily_pnl": "-250.00", "daily_loss_limit": null, "kill_switches_active": []}""")

        val status = repository.getRiskStatus("p-1").getOrThrow()

        assertNull(status.dailyLossUsagePct)
    }

    @Test
    fun `getRiskStatus daily loss usage is zero on a winning day`() = runTest {
        stubHealthyReads()
        responses[summaryPath] = json(200, """{"daily_pnl": "120.50", "daily_loss_limit": "500.0", "kill_switches_active": []}""")

        val status = repository.getRiskStatus("p-1").getOrThrow()

        assertEquals(0.0, status.dailyLossUsagePct!!, 1e-9)
    }

    @Test
    fun `getRiskStatus daily loss usage exceeds one when the loss is beyond the limit`() = runTest {
        stubHealthyReads()
        responses[summaryPath] = json(200, """{"daily_pnl": "-612.40", "daily_loss_limit": "500.0", "kill_switches_active": []}""")

        val status = repository.getRiskStatus("p-1").getOrThrow()

        assertEquals(1.2248, status.dailyLossUsagePct!!, 1e-9)
    }

    // ── activatePortfolioKillSwitch ────────────────────────────────────────

    private val activatedJson = """
        {"scope": "portfolio:p-1", "active": true, "reason": "Arrêt manuel", "activated_by": "12",
         "activated_at": "2026-09-29T09:02:11.532109Z"}
    """.trimIndent()

    @Test
    fun `activate confirms on a 200 and sends one POST with the reason only`() = runTest {
        responses[activatePath] = json(200, activatedJson)

        val outcome = repository.activatePortfolioKillSwitch("p-1", "Arrêt manuel").getOrThrow()

        assertEquals(WriteOutcome.CONFIRMED, outcome)
        assertEquals(1, server.requestCount)
        val request = seenRequests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/risk/portfolios/p-1/kill-switch", request.path)
        assertEquals("""{"reason":"Arrêt manuel"}""", request.body.readUtf8())
    }

    @Test
    fun `activate confirms on a 201`() = runTest {
        responses[activatePath] = json(201, activatedJson)

        assertEquals(WriteOutcome.CONFIRMED, repository.activatePortfolioKillSwitch("p-1", "Arrêt").getOrThrow())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `activate reports unconfirmed on a 500 without retrying`() = runTest {
        responses[activatePath] = json(500, """{"message":"Internal error"}""")

        val outcome = repository.activatePortfolioKillSwitch("p-1", "Arrêt").getOrThrow()

        assertEquals(WriteOutcome.REQUESTED_UNCONFIRMED, outcome)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `activate reports unconfirmed on a read timeout after send, with a single request`() = runTest {
        responses[activatePath] = MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)

        val outcome = repository.activatePortfolioKillSwitch("p-1", "Arrêt").getOrThrow()

        assertEquals(WriteOutcome.REQUESTED_UNCONFIRMED, outcome)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `activate fails with the HTTP code on a 403`() = runTest {
        responses[activatePath] = json(403, """{"message":"Access to this portfolio is not allowed."}""")

        val error = repository.activatePortfolioKillSwitch("p-1", "Arrêt").exceptionOrNull()

        assertTrue(error is HttpStatusException)
        assertEquals(403, (error as HttpStatusException).code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `activate fails with the HTTP code on a 422`() = runTest {
        responses[activatePath] = json(422, """{"message":"Validation error"}""")

        val error = repository.activatePortfolioKillSwitch("p-1", "Arrêt").exceptionOrNull()

        assertTrue(error is HttpStatusException)
        assertEquals(422, (error as HttpStatusException).code)
    }

    @Test
    fun `activate rejects a blank reason without any request`() = runTest {
        val error = repository.activatePortfolioKillSwitch("p-1", "   ").exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the risk APIs never expose the kill switch deactivation`() {
        val deleteAnnotation = retrofit2.http.DELETE::class.java
        assertTrue(RiskApi::class.java.methods.none { it.isAnnotationPresent(deleteAnnotation) })
        assertTrue(RiskWriteApi::class.java.methods.none { it.isAnnotationPresent(deleteAnnotation) })
    }
}
