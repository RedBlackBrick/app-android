package com.tradingplatform.app.data.repository

import com.squareup.moshi.Moshi
import com.tradingplatform.app.data.api.PreferencesApi
import com.tradingplatform.app.data.api.PreferencesWriteApi
import com.tradingplatform.app.data.model.BigDecimalAdapter
import com.tradingplatform.app.data.model.InstantAdapter
import com.tradingplatform.app.domain.exception.HttpStatusException
import com.tradingplatform.app.domain.model.NotifCategory
import com.tradingplatform.app.domain.model.RiskAlertThresholds
import com.tradingplatform.app.domain.model.WriteOutcome
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import java.util.concurrent.TimeUnit

/**
 * Read-modify-write des préférences push via les vraies interfaces Retrofit contre un
 * MockWebServer. Le backend fusionne `ui` de façon SUPERFICIELLE : le PATCH doit contenir
 * `ui.notifications` et `ui.appearance` COMPLETS (une seule valeur modifiée), sans perdre de clé
 * inconnue ni de `null` explicite, en une seule requête PATCH.
 */
class NotificationPreferencesRepositoryImplTest {

    private val server = MockWebServer()
    private lateinit var repository: NotificationPreferencesRepositoryImpl

    private val fullPreferencesJson = """
        {"ui":{
          "appearance":{"timezone":"Europe/Paris","dateFormat":"DD/MM/YYYY","use24h":true,"density":"normal","avatarColor":"indigo","futureAppearanceKey":{"a":1}},
          "notifications":{
            "notifTypes":[
              {"key":"strategy_signal","inApp":true,"push":true,"email":false},
              {"key":"risk_alert","inApp":true,"push":true,"email":true,"customFlag":"x"},
              {"key":"system","inApp":true,"push":false,"email":true}
            ],
            "quietHours":{"enabled":true,"start":"22:30","end":"07:00"},
            "riskAlertThresholds":{"varPctOfMax":80.5,"suppressedSignalsPerDay":25,"drawdownWarnPct":null,"positionConcentrationPct":60},
            "experimental":{"nested":[1,2,{"z":null}]}
          },
          "theme":"dark"
        },"trading":{"default_risk_profile":"moderate"}}
    """.trimIndent()

    @Before
    fun setUp() {
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
        repository = NotificationPreferencesRepositoryImpl(
            api = readRetrofit.create(PreferencesApi::class.java),
            writeApi = writeRetrofit.create(PreferencesWriteApi::class.java),
            moshi = moshi,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueueGet(body: String) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
    }

    private fun enqueuePatch(code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody("""{"ui":{},"trading":{}}"""))
    }

    // ── setPushEnabled : corps du PATCH ────────────────────────────────────

    @Test
    fun `setPushEnabled sends complete notifications and appearance with a single value changed`() = runTest {
        enqueueGet(fullPreferencesJson)
        enqueuePatch()

        val outcome = repository.setPushEnabled(NotifCategory.RISK_ALERT, false).getOrThrow()

        assertEquals(WriteOutcome.CONFIRMED, outcome)
        val get = server.takeRequest()
        assertEquals("GET", get.method)
        assertEquals("/v1/auth/preferences", get.path)
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("/v1/auth/preferences", patch.path)
        assertTrue(patch.getHeader("Content-Type")!!.startsWith("application/json"))
        // Seul `push` de risk_alert passe de true à false. Clé inconnue (`customFlag`, `experimental`,
        // `futureAppearanceKey`), `null` explicite, ordre des clés : conservés. `theme` (clé de premier
        // niveau non modifiée) et `trading` ne sont pas envoyés : ils survivent côté serveur.
        assertEquals(
            """{"ui":{"notifications":{"notifTypes":[""" +
                """{"key":"strategy_signal","inApp":true,"push":true,"email":false},""" +
                """{"key":"risk_alert","inApp":true,"push":false,"email":true,"customFlag":"x"},""" +
                """{"key":"system","inApp":true,"push":false,"email":true}],""" +
                """"quietHours":{"enabled":true,"start":"22:30","end":"07:00"},""" +
                """"riskAlertThresholds":{"varPctOfMax":80.5,"suppressedSignalsPerDay":25,"drawdownWarnPct":null,"positionConcentrationPct":60},""" +
                """"experimental":{"nested":[1,2,{"z":null}]}},""" +
                """"appearance":{"timezone":"Europe/Paris","dateFormat":"DD/MM/YYYY","use24h":true,"density":"normal","avatarColor":"indigo","futureAppearanceKey":{"a":1}}}}""",
            patch.body.readUtf8(),
        )
        // Une relecture, un seul PATCH : rien d'autre.
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `setPushEnabled can turn a disabled push back on`() = runTest {
        enqueueGet(fullPreferencesJson)
        enqueuePatch()

        repository.setPushEnabled(NotifCategory.SYSTEM, true).getOrThrow()

        server.takeRequest()
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("""{"key":"system","inApp":true,"push":true,"email":true}"""))
        assertTrue(body.contains("""{"key":"risk_alert","inApp":true,"push":true,"email":true,"customFlag":"x"}"""))
    }

    @Test
    fun `setPushEnabled on a fresh account creates only the requested category with defaults`() = runTest {
        enqueueGet("""{"ui":{},"trading":{}}""")
        enqueuePatch()

        repository.setPushEnabled(NotifCategory.SYSTEM, false).getOrThrow()

        server.takeRequest()
        assertEquals(
            """{"ui":{"notifications":{"notifTypes":[{"key":"system","inApp":true,"push":false,"email":true}]}}}""",
            server.takeRequest().body.readUtf8(),
        )
    }

    @Test
    fun `setPushEnabled appends a missing category and keeps the existing entries`() = runTest {
        enqueueGet(
            """{"ui":{"notifications":{"notifTypes":[{"key":"risk_alert","inApp":false,"push":true,"email":true}],"quietHours":{"enabled":false,"start":"22:00","end":"08:00"}}}}""",
        )
        enqueuePatch()

        repository.setPushEnabled(NotifCategory.STRATEGY_SIGNAL, false).getOrThrow()

        server.takeRequest()
        assertEquals(
            """{"ui":{"notifications":{"notifTypes":[""" +
                """{"key":"risk_alert","inApp":false,"push":true,"email":true},""" +
                """{"key":"strategy_signal","inApp":true,"push":false,"email":true}],""" +
                """"quietHours":{"enabled":false,"start":"22:00","end":"08:00"}}}}""",
            server.takeRequest().body.readUtf8(),
        )
    }

    // ── setPushEnabled : issues ────────────────────────────────────────────

    @Test
    fun `setPushEnabled fails without any PATCH when the read fails`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val error = repository.setPushEnabled(NotifCategory.RISK_ALERT, false).exceptionOrNull()

        assertTrue(error is HttpStatusException)
        assertEquals(500, (error as HttpStatusException).code)
        assertEquals(1, server.requestCount)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun `setPushEnabled reports unconfirmed on a 500 PATCH and never retries`() = runTest {
        enqueueGet(fullPreferencesJson)
        enqueuePatch(code = 500)

        val outcome = repository.setPushEnabled(NotifCategory.RISK_ALERT, false).getOrThrow()

        assertEquals(WriteOutcome.REQUESTED_UNCONFIRMED, outcome)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `setPushEnabled reports unconfirmed on a PATCH read timeout with a single PATCH`() = runTest {
        enqueueGet(fullPreferencesJson)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val outcome = repository.setPushEnabled(NotifCategory.RISK_ALERT, false).getOrThrow()

        assertEquals(WriteOutcome.REQUESTED_UNCONFIRMED, outcome)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `setPushEnabled fails with the HTTP code on a 422 PATCH`() = runTest {
        enqueueGet(fullPreferencesJson)
        enqueuePatch(code = 422)

        val error = repository.setPushEnabled(NotifCategory.RISK_ALERT, false).exceptionOrNull()

        assertTrue(error is HttpStatusException)
        assertEquals(422, (error as HttpStatusException).code)
        assertEquals(2, server.requestCount)
    }

    // ── get ────────────────────────────────────────────────────────────────

    @Test
    fun `get maps channels, quiet hours and thresholds as fractions`() = runTest {
        enqueueGet(fullPreferencesJson)

        val prefs = repository.get().getOrThrow()

        assertEquals(3, prefs.categories.size)
        assertFalse(prefs.channelsFor(NotifCategory.STRATEGY_SIGNAL).email)
        assertTrue(prefs.channelsFor(NotifCategory.STRATEGY_SIGNAL).push)
        assertTrue(prefs.isPushEnabled(NotifCategory.RISK_ALERT))
        assertFalse(prefs.isPushEnabled(NotifCategory.SYSTEM))
        assertTrue(prefs.quietHours.enabled)
        assertEquals("22:30", prefs.quietHours.start)
        assertEquals("07:00", prefs.quietHours.end)
        // Backend : 80.5 / 25 / null / 60 (pourcentages) -> domaine : fractions.
        assertEquals(0.805, prefs.riskAlertThresholds.varPctOfMax!!, 1e-9)
        assertEquals(25, prefs.riskAlertThresholds.suppressedSignalsPerDay)
        assertNull(prefs.riskAlertThresholds.drawdownWarnPct)
        assertEquals(0.6, prefs.riskAlertThresholds.positionConcentrationPct!!, 1e-9)
    }

    @Test
    fun `get on a fresh account returns every channel enabled`() = runTest {
        enqueueGet("""{"ui":{},"trading":{}}""")

        val prefs = repository.get().getOrThrow()

        NotifCategory.entries.forEach { category ->
            val channels = prefs.channelsFor(category)
            assertTrue(channels.inApp && channels.push && channels.email)
        }
        assertFalse(prefs.quietHours.enabled)
        assertEquals(RiskAlertThresholds(), prefs.riskAlertThresholds)
    }
}
