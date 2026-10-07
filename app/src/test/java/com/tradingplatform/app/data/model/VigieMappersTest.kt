package com.tradingplatform.app.data.model

import com.squareup.moshi.Moshi
import com.tradingplatform.app.domain.model.VigieFlag
import com.tradingplatform.app.domain.model.VigieIndicatorStatus
import com.tradingplatform.app.domain.model.VigieLevel
import com.tradingplatform.app.domain.model.VigieRefugeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Décodage Moshi RÉEL du contrat `GET /v1/vigie` (audit/vigie/API.md) puis mapping DTO → domaine :
 * un niveau / statut / drapeau / refuge inconnu ne devient jamais « normal », `available = false`
 * n'est jamais un niveau, et un `null` explicite (liste, sous-objet) ne fait pas planter le décodage.
 */
class VigieMappersTest {

    private val adapter = Moshi.Builder().build().adapter(VigieDto::class.java)

    private fun decode(json: String): VigieDto = requireNotNull(adapter.fromJson(json))

    private val fullJson = """
        {
          "available": true,
          "as_of": "2026-10-06",
          "computed_at": "2026-10-06T12:02:11+00:00",
          "is_stale": false,
          "methodology_version": "1",
          "score": 1.25,
          "level": "normal",
          "level_label": "Normal",
          "level_raw": "vigilance",
          "coverage": 1.0,
          "live_indicators": 14, "expected_indicators": 14,
          "live_families": 5, "expected_families": 5,
          "most_stressed_family": {"code": "F1", "label": "Taux et refuge obligataire", "score": 1.9},
          "cutoffs": {"vigilance": 2.4, "alerte": 3.75, "crise": 5.85},
          "families": [
            {"code": "F1", "label": "Taux et refuge obligataire", "score": 1.9, "live": 4, "expected": 4, "coverage": 1.0},
            {"code": "F3", "label": "Crédit", "score": null, "live": 0, "expected": 2, "coverage": 0.0}
          ],
          "indicators": [
            {"code": "RATES_4W", "family": "F1", "label": "Taux 10 ans, 4 semaines", "unit": "pb",
             "value": 53.0, "score": 2, "status": "ok", "age_days": 4, "orange": 25.0, "red": 50.0,
             "higher_is_worse": true, "detail": "DGS10 +53 pb"},
            {"code": "HY_OAS", "family": "F3", "label": "Spread HY", "unit": "%",
             "value": null, "score": null, "status": "stale", "age_days": 12, "orange": 4.0, "red": 6.0,
             "higher_is_worse": true, "detail": "périmé"}
          ],
          "regime": {
            "as_of": "2026-10-06",
            "sahm": {"code": "SAHM", "label": "Règle de Sahm", "unit": "pts", "value": 0.2, "status": "ok", "age_days": 30, "flag": "ok", "detail": "d"},
            "t10y3m": {"code": "T10Y3M", "label": "Pente 10a-3m", "unit": "pts", "value": null, "status": "missing", "age_days": null, "flag": null, "detail": "d"},
            "spy_tlt_corr_change": {"code": "C", "label": "Corr", "unit": "", "value": 0.1, "status": "ok", "age_days": 1, "flag": "magenta", "detail": "d"},
            "baa10y": {"code": "BAA10Y", "label": "BAA-10Y", "unit": "%", "value": 2.1, "status": "ok", "age_days": 1, "flag": "orange", "detail": "d"}
          },
          "refuge": {
            "as_of": "2026-10-06", "state": "partial",
            "legs": [
              {"code": "L1", "label": "Taux", "met": true, "value": 1.2, "sigma": 0.8, "threshold": 0.4, "detail": "d"},
              {"code": "L2", "label": "Or", "met": null, "value": null, "sigma": null, "threshold": null, "detail": "d"}
            ],
            "legs_met": 1, "legs_unknown": 1
          },
          "sparkline": [
            {"date": "2026-07-08", "score": 0.5, "level": "normal"},
            {"date": "2026-07-09", "score": null, "level": "unknown"}
          ]
        }
    """.trimIndent()

    @Test
    fun `a full payload maps every field to the domain`() {
        val vigie = decode(fullJson).toDomain()

        assertTrue(vigie.available)
        assertEquals(LocalDate.of(2026, 10, 6), vigie.asOf)
        // computed_at arrive avec un offset numérique (Python .isoformat()) : doit être lu.
        assertEquals(1_791_288_131_000L, vigie.computedAt?.toEpochMilli())
        assertEquals(1.25, vigie.score!!, 0.0)
        assertEquals(VigieLevel.NORMAL, vigie.level)
        assertEquals(VigieLevel.VIGILANCE, vigie.levelRaw)
        assertEquals(14, vigie.liveIndicators)
        assertEquals("F1", vigie.mostStressedFamily?.code)
        assertEquals(2.4, vigie.cutoffs?.vigilance!!, 0.0)
        assertEquals(2, vigie.families.size)
        assertNull(vigie.families[1].score)
        assertEquals(VigieIndicatorStatus.OK, vigie.indicators[0].status)
        assertEquals(VigieIndicatorStatus.STALE, vigie.indicators[1].status)
        assertNull(vigie.indicators[1].score)
        assertEquals(VigieRefugeState.PARTIAL, vigie.refuge?.state)
        assertEquals(true, vigie.refuge?.legs?.get(0)?.met)
        assertNull(vigie.refuge?.legs?.get(1)?.met)
        assertEquals(2, vigie.sparkline.size)
        assertNull(vigie.sparkline[1].score)
        assertEquals(VigieLevel.UNKNOWN, vigie.sparkline[1].level)
    }

    @Test
    fun `regime keeps the backend order, a null flag stays null and an unknown flag becomes UNKNOWN`() {
        val regime = decode(fullJson).toDomain().regime!!

        assertEquals(listOf("SAHM", "T10Y3M", "C", "BAA10Y"), regime.indicators.map { it.code })
        assertEquals(VigieFlag.OK, regime.indicators[0].flag)
        assertNull(regime.indicators[1].flag)
        assertEquals(VigieIndicatorStatus.MISSING, regime.indicators[1].status)
        assertEquals(VigieFlag.UNKNOWN, regime.indicators[2].flag)
        assertEquals(VigieFlag.ORANGE, regime.indicators[3].flag)
    }

    @Test
    fun `an unknown level is UNKNOWN, never normal, and its score is discarded`() {
        val json = fullJson.replace("\"level\": \"normal\"", "\"level\": \"catastrophe\"")

        val vigie = decode(json).toDomain()

        assertEquals(VigieLevel.UNKNOWN, vigie.level)
        assertNull(vigie.score)
    }

    @Test
    fun `the server level unknown has no score`() {
        val json = fullJson
            .replace("\"level\": \"normal\"", "\"level\": \"unknown\"")
            .replace("\"level_raw\": \"vigilance\"", "\"level_raw\": \"unknown\"")

        val vigie = decode(json).toDomain()

        assertEquals(VigieLevel.UNKNOWN, vigie.level)
        assertNull(vigie.score)
        assertTrue(vigie.available)
    }

    @Test
    fun `an unknown refuge state and an unknown indicator status are UNKNOWN`() {
        val json = fullJson
            .replace("\"state\": \"partial\"", "\"state\": \"enorme\"")
            .replace("\"status\": \"stale\"", "\"status\": \"zombie\"")

        val vigie = decode(json).toDomain()

        assertEquals(VigieRefugeState.UNKNOWN, vigie.refuge?.state)
        assertEquals(VigieIndicatorStatus.UNKNOWN, vigie.indicators[1].status)
    }

    @Test
    fun `available false maps to UNAVAILABLE even with explicit nulls and empty lists`() {
        val json = """
            {"available": false, "as_of": null, "computed_at": null, "is_stale": true,
             "methodology_version": null, "score": null, "level": null, "level_label": null,
             "level_raw": null, "coverage": null, "live_indicators": null, "expected_indicators": null,
             "live_families": null, "expected_families": null, "most_stressed_family": null,
             "cutoffs": null, "families": [], "indicators": null, "regime": null, "refuge": null,
             "sparkline": []}
        """.trimIndent()

        val vigie = decode(json).toDomain()

        assertSame(com.tradingplatform.app.domain.model.Vigie.UNAVAILABLE, vigie)
        assertFalse(vigie.available)
        assertEquals(VigieLevel.UNKNOWN, vigie.level)
        assertTrue(vigie.isStale)
    }

    @Test
    fun `available false wins even when the payload still carries a readable as_of and a normal level`() {
        // Un serveur incohérent (available=false mais as_of/level/score présents) ne doit JAMAIS
        // produire un niveau affichable : `available` fait foi, pas la présence des autres champs.
        val json = fullJson.replaceFirst("\"available\": true", "\"available\": false")

        val vigie = decode(json).toDomain()

        assertSame(com.tradingplatform.app.domain.model.Vigie.UNAVAILABLE, vigie)
        assertFalse(vigie.available)
        assertEquals(VigieLevel.UNKNOWN, vigie.level)
        assertNull(vigie.score)
    }

    @Test
    fun `a bare available false object decodes`() {
        val vigie = decode("""{"available": false}""").toDomain()

        assertFalse(vigie.available)
        assertEquals(VigieLevel.UNKNOWN, vigie.level)
    }

    @Test
    fun `an available payload without a readable as_of is treated as unavailable, never as a level`() {
        // Première occurrence = le `as_of` de premier niveau (regime / refuge ont le leur, plus loin).
        val json = fullJson.replaceFirst("\"as_of\": \"2026-10-06\"", "\"as_of\": \"pas une date\"")

        val vigie = decode(json).toDomain()

        assertFalse(vigie.available)
        assertEquals(VigieLevel.UNKNOWN, vigie.level)
    }

    @Test
    fun `null lists on an available payload become empty lists`() {
        val vigie = decode(
            """{"available": true, "as_of": "2026-10-06", "is_stale": false, "level": "normal", "score": 0.5,
                "families": null, "indicators": null, "sparkline": null, "regime": null, "refuge": null}""",
        ).toDomain()

        assertTrue(vigie.available)
        assertTrue(vigie.families.isEmpty())
        assertTrue(vigie.indicators.isEmpty())
        assertTrue(vigie.sparkline.isEmpty())
        assertNull(vigie.regime)
        assertNull(vigie.refuge)
    }

    @Test
    fun `wire parsers fall back to UNKNOWN on null, blank and garbage`() {
        assertEquals(VigieLevel.UNKNOWN, VigieLevel.fromWire(null))
        assertEquals(VigieLevel.UNKNOWN, VigieLevel.fromWire(""))
        assertEquals(VigieLevel.UNKNOWN, VigieLevel.fromWire("rouge"))
        assertEquals(VigieLevel.CRISE, VigieLevel.fromWire(" CRISE "))
        assertEquals(VigieRefugeState.UNKNOWN, VigieRefugeState.fromWire(null))
        assertNull(VigieFlag.fromWire(null))
    }
}
