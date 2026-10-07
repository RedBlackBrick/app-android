package com.tradingplatform.app.ui.screens.vigie

import com.tradingplatform.app.domain.model.Vigie
import com.tradingplatform.app.domain.model.VigieCutoffs
import com.tradingplatform.app.domain.model.VigieFamily
import com.tradingplatform.app.domain.model.VigieIndicator
import com.tradingplatform.app.domain.model.VigieIndicatorStatus
import com.tradingplatform.app.domain.model.VigieLevel
import com.tradingplatform.app.domain.model.VigieMostStressedFamily
import com.tradingplatform.app.domain.model.VigieRefuge
import com.tradingplatform.app.domain.model.VigieRefugeState
import com.tradingplatform.app.domain.model.VigieSparklinePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Logique pure de la Vigie : libellés, tons, formatage, « le global Normal ne masque pas F1 rouge ». */
class VigiePresentationTest {

    private fun family(code: String, score: Double?, label: String = "Famille $code") =
        VigieFamily(code, label, score, live = if (score == null) 0 else 3, expected = 3, coverage = 1.0)

    /** Global « Normal » (1,25) avec F1 à 1,9/2 : le cas qui ne doit jamais se lire « tout va bien ». */
    private fun normalWithRedF1(level: VigieLevel = VigieLevel.NORMAL) = Vigie.UNAVAILABLE.copy(
        available = true,
        asOf = LocalDate.of(2026, 10, 6),
        isStale = false,
        score = 1.25,
        level = level,
        levelRaw = level,
        coverage = 1.0,
        liveIndicators = 14,
        expectedIndicators = 14,
        liveFamilies = 5,
        expectedFamilies = 5,
        mostStressedFamily = VigieMostStressedFamily("F1", "Taux et refuge obligataire", 1.9),
        families = listOf(
            family("F1", 1.9, "Taux et refuge obligataire"),
            family("F2", 0.0),
            family("F3", 0.0),
            family("F4", 0.5),
            family("F5", 0.0),
        ),
        refuge = VigieRefuge(LocalDate.of(2026, 10, 6), VigieRefugeState.NONE, emptyList(), 0, 0),
    )

    // ── Niveau → libellé / ton ───────────────────────────────────────────────

    @Test
    fun `levels map to labels and semantic tones, unknown is neutral and never green`() {
        assertEquals("Normal", vigieLevelLabel(VigieLevel.NORMAL))
        assertEquals("Vigilance", vigieLevelLabel(VigieLevel.VIGILANCE))
        assertEquals("Alerte", vigieLevelLabel(VigieLevel.ALERTE))
        assertEquals("Crise", vigieLevelLabel(VigieLevel.CRISE))
        assertEquals("Données insuffisantes", vigieLevelLabel(VigieLevel.UNKNOWN))

        assertEquals(VigieTone.NORMAL, VigieLevel.NORMAL.tone())
        assertEquals(VigieTone.VIGILANCE, VigieLevel.VIGILANCE.tone())
        assertEquals(VigieTone.ALERTE, VigieLevel.ALERTE.tone())
        assertEquals(VigieTone.CRISE, VigieLevel.CRISE.tone())
        assertEquals(VigieTone.NEUTRAL, VigieLevel.UNKNOWN.tone())
    }

    @Test
    fun `tone severity orders neutral lowest then normal to crise`() {
        val ordered = listOf(VigieTone.NEUTRAL, VigieTone.NORMAL, VigieTone.VIGILANCE, VigieTone.ALERTE, VigieTone.CRISE)
        assertEquals(ordered.map { it.severity }, ordered.map { it.severity }.sorted())
        assertEquals(0, VigieTone.NEUTRAL.severity)
        assertTrue(VigieTone.NORMAL.severity > VigieTone.NEUTRAL.severity)
    }

    // ── Le global Normal ne masque pas une famille rouge ─────────────────────

    @Test
    fun `a Normal global with a red F1 keeps the most stressed family visible and flags the masking`() {
        val model = vigieCardModel(normalWithRedF1())

        assertEquals("Normal", model.levelLabel)
        assertEquals(VigieTone.NORMAL, model.tone)
        assertEquals("Famille la plus tendue : Taux et refuge obligataire · 1,9 / 2", model.stressedText)
        assertTrue(model.masksStress)
        assertEquals(VigieTone.ALERTE, model.stressedTone)
        // F1 est la barre mise en avant, les 5 familles sont toutes présentes.
        assertEquals(5, model.families.size)
        assertEquals(listOf("F1"), model.families.filter { it.isHighlighted }.map { it.code })
        assertEquals(0.95f, model.families[0].fraction, 0.0001f)
        // Le texte lu à voix haute porte l'avertissement, pas seulement « Normal ».
        assertTrue(model.spokenDescription.contains("Famille la plus tendue"))
        assertTrue(model.spokenDescription.contains("moyenne des 5 familles"))
    }

    @Test
    fun `no masking when the global level is as severe as the stressed family or calm everywhere`() {
        val calm = normalWithRedF1().copy(
            mostStressedFamily = VigieMostStressedFamily("F4", "Plomberie financière", 0.2),
        )
        assertFalse(vigieCardModel(calm).masksStress)
        // Quand même visible : la famille la plus tendue est TOUJOURS affichée.
        assertNotNull(vigieCardModel(calm).stressedText)

        val crise = normalWithRedF1(VigieLevel.CRISE)
        assertFalse(vigieCardModel(crise).masksStress)
    }

    @Test
    fun `an unknown global never reads as reassuring nor claims masking`() {
        assertFalse(masksFamilyStress(VigieLevel.UNKNOWN, VigieMostStressedFamily("F1", "x", 2.0)))
        assertFalse(masksFamilyStress(VigieLevel.NORMAL, null))
    }

    @Test
    fun `family tone has the same 3 bands as the web - green, orange, red - never Crise`() {
        assertEquals(VigieTone.NEUTRAL, familyTone(null))
        assertEquals(VigieTone.NEUTRAL, familyTone(Double.NaN))
        assertEquals(VigieTone.NEUTRAL, familyTone(Double.POSITIVE_INFINITY))
        assertEquals(VigieTone.NORMAL, familyTone(0.0))
        assertEquals(VigieTone.NORMAL, familyTone(0.49))
        assertEquals(VigieTone.VIGILANCE, familyTone(0.5))
        // L'exemple d'API.md (F1 = 1,25) est ORANGE, comme sur le web — pas « alerte ».
        assertEquals(VigieTone.VIGILANCE, familyTone(1.25))
        assertEquals(VigieTone.VIGILANCE, familyTone(1.49))
        assertEquals(VigieTone.ALERTE, familyTone(1.5))
        assertEquals(VigieTone.ALERTE, familyTone(2.0))
        // Même bande qu'un indicateur à l'entier le plus proche : 0 -> vert, 1 -> orange, 2 -> rouge.
        listOf(0, 1, 2).forEach { assertEquals(indicatorTone(it), familyTone(it.toDouble())) }
        // Aucune famille n'est jamais « Crise » (réservé au niveau global).
        listOf(0.0, 0.5, 1.0, 1.25, 1.5, 1.9, 2.0).forEach { assertTrue(familyTone(it) != VigieTone.CRISE) }
    }

    @Test
    fun `an orange family (F1 = 1,25) is flagged as masked by a Normal global but not by Vigilance or worse`() {
        val orangeF1 = VigieMostStressedFamily("F1", "Taux et refuge obligataire", 1.25)

        assertTrue(masksFamilyStress(VigieLevel.NORMAL, orangeF1))
        assertFalse(masksFamilyStress(VigieLevel.VIGILANCE, orangeF1))
        assertFalse(masksFamilyStress(VigieLevel.ALERTE, orangeF1))
        assertFalse(masksFamilyStress(VigieLevel.CRISE, orangeF1))

        val model = vigieCardModel(normalWithRedF1().copy(mostStressedFamily = orangeF1, families = listOf(family("F1", 1.25))))
        assertEquals(VigieTone.VIGILANCE, model.stressedTone)
        assertEquals(VigieTone.VIGILANCE, model.families.single().tone)
        assertTrue(model.masksStress)
    }

    @Test
    fun `a red family (1,5 and above) is masked by Normal and Vigilance globals, not by Alerte or Crise`() {
        val red = VigieMostStressedFamily("F1", "x", 1.5)

        assertTrue(masksFamilyStress(VigieLevel.NORMAL, red))
        assertTrue(masksFamilyStress(VigieLevel.VIGILANCE, red))
        assertFalse(masksFamilyStress(VigieLevel.ALERTE, red))
        assertFalse(masksFamilyStress(VigieLevel.CRISE, red))
        assertEquals(VigieTone.ALERTE, vigieCardModel(normalWithRedF1().copy(families = listOf(family("F1", 1.5)))).families.single().tone)
    }

    @Test
    fun `the global level keeps its own 4 tones, Crise included`() {
        assertEquals(VigieTone.CRISE, VigieLevel.CRISE.tone())
        assertEquals(VigieTone.ALERTE, VigieLevel.ALERTE.tone())
    }

    @Test
    fun `a family without a score is an empty neutral bar`() {
        val vigie = normalWithRedF1().copy(families = listOf(family("F3", null)))

        val bar = vigieCardModel(vigie).families.single()

        assertEquals(0f, bar.fraction, 0f)
        assertEquals(VigieTone.NEUTRAL, bar.tone)
        assertEquals("—", bar.scoreText)
    }

    // ── Données insuffisantes ────────────────────────────────────────────────

    @Test
    fun `unavailable is the neutral insufficient-data state, never a level`() {
        val model = vigieCardModel(Vigie.UNAVAILABLE)

        assertFalse(model.hasLevel)
        assertEquals("Données insuffisantes", model.levelLabel)
        assertEquals(VigieTone.NEUTRAL, model.tone)
        assertEquals("—", model.scoreText)
        assertNull(model.stressedText)
        assertNull(model.refugeText)
        assertFalse(model.masksStress)
        assertTrue(model.families.isEmpty())
        assertFalse(model.showStaleBadge)
    }

    @Test
    fun `an unknown level on an available payload is neutral with no score even if one is present`() {
        val model = vigieCardModel(normalWithRedF1(VigieLevel.UNKNOWN))

        assertFalse(model.hasLevel)
        assertEquals("Données insuffisantes", model.levelLabel)
        assertEquals(VigieTone.NEUTRAL, model.tone)
        assertEquals("—", model.scoreText)
        assertFalse(model.masksStress)
    }

    // ── Formatage ────────────────────────────────────────────────────────────

    @Test
    fun `score is shown with one decimal and a French comma`() {
        assertEquals("1,3", formatVigieScore(1.25)) // HALF_UP, pas HALF_EVEN
        assertEquals("0,0", formatVigieScore(0.0))
        assertEquals("10,0", formatVigieScore(10.0))
        assertEquals("—", formatVigieScore(null))
        assertEquals("—", formatVigieScore(Double.NaN))
        assertEquals("1,3", vigieCardModel(normalWithRedF1()).scoreText)
    }

    @Test
    fun `numbers drop trailing zeros and keep at most two decimals`() {
        assertEquals("53", formatVigieNumber(53.0))
        assertEquals("1,9", formatVigieNumber(1.9))
        assertEquals("0,13", formatVigieNumber(0.125)) // HALF_UP
        assertEquals("-3", formatVigieNumber(-3.0))
        assertEquals("—", formatVigieNumber(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `coverage is always shown as live over expected`() {
        assertEquals("14/14 indicateurs", formatCoverage(14, 14))
        assertEquals("11/14 indicateurs", formatCoverage(11, 14))
        assertEquals("Couverture —", formatCoverage(0, 0))
        assertEquals("14/14 indicateurs", vigieCardModel(normalWithRedF1()).coverageText)
    }

    @Test
    fun `as of date is formatted day month year`() {
        assertEquals("06/10/2026", formatAsOf(LocalDate.of(2026, 10, 6)))
        assertNull(formatAsOf(null))
        assertEquals("06/10/2026", vigieCardModel(normalWithRedF1()).asOfText)
    }

    @Test
    fun `stale badge follows the server is_stale flag`() {
        assertFalse(vigieCardModel(normalWithRedF1()).showStaleBadge)
        assertTrue(vigieCardModel(normalWithRedF1().copy(isStale = true)).showStaleBadge)
    }

    @Test
    fun `refuge labels and tones, only a proven none is green`() {
        assertEquals("Refuge : aucun", refugeLabel(VigieRefugeState.NONE))
        assertEquals("Refuge : partiel", refugeLabel(VigieRefugeState.PARTIAL))
        assertEquals("Refuge : complet", refugeLabel(VigieRefugeState.FULL))
        assertEquals("Refuge : inconnu", refugeLabel(VigieRefugeState.UNKNOWN))
        assertEquals(VigieTone.NORMAL, refugeTone(VigieRefugeState.NONE))
        assertEquals(VigieTone.NEUTRAL, refugeTone(VigieRefugeState.UNKNOWN))
        assertEquals("Refuge : aucun", vigieCardModel(normalWithRedF1()).refugeText)
    }

    @Test
    fun `the card always carries the not-a-forecast wording`() {
        assertEquals("Veille coïncidente, pas une prévision", VIGIE_DISCLAIMER)
        assertTrue(vigieCardModel(normalWithRedF1()).spokenDescription.endsWith("$VIGIE_DISCLAIMER."))
        assertTrue(vigieCardModel(Vigie.UNAVAILABLE).spokenDescription.endsWith("$VIGIE_DISCLAIMER."))
        // Le mot « prévision » n'apparaît que dans sa négation (« pas une prévision »).
        assertFalse(VIGIE_COINCIDENT_NOTE.contains("prévision"))
        assertTrue(VIGIE_COINCIDENT_NOTE.contains("ne prédit pas"))
    }

    // ── Courbe ───────────────────────────────────────────────────────────────

    private fun point(score: Double?) = VigieSparklinePoint(LocalDate.of(2026, 7, 8), score, VigieLevel.NORMAL)

    @Test
    fun `sparkline needs at least two known points and keeps gaps`() {
        val one = normalWithRedF1().copy(sparkline = listOf(point(1.0), point(null)))
        assertNull(vigieCardModel(one).sparkline)

        val two = normalWithRedF1().copy(sparkline = listOf(point(1.0), point(null), point(2.0)))
        assertEquals(listOf(1.0, null, 2.0), vigieCardModel(two).sparkline)
    }

    @Test
    fun `sparkline axis starts at zero and fits the observed max or the vigilance cutoff`() {
        val cutoffs = VigieCutoffs(2.4, 3.75, 5.85)
        assertEquals(2.4 * 1.1, sparklineAxisMax(listOf(0.5, 1.0), cutoffs), 1e-9)
        assertEquals(5.0 * 1.1, sparklineAxisMax(listOf(0.5, 5.0, null), cutoffs), 1e-9)
        assertEquals(1.0 * 1.1, sparklineAxisMax(emptyList(), null), 1e-9)
    }

    @Test
    fun `sparkline guides keep only cutoffs inside the axis`() {
        val cutoffs = VigieCutoffs(2.4, 3.75, 5.85)
        assertEquals(listOf(2.4, 3.75), sparklineGuides(cutoffs, axisMax = 4.0))
        assertEquals(emptyList<Double>(), sparklineGuides(null, axisMax = 4.0))
    }

    // ── Écran détail ─────────────────────────────────────────────────────────

    private fun indicator(
        value: Double? = 53.0,
        unit: String = "pb",
        score: Int? = 2,
        status: VigieIndicatorStatus = VigieIndicatorStatus.OK,
        higherIsWorse: Boolean = true,
        orange: Double = 25.0,
        red: Double = 50.0,
    ) = VigieIndicator("RATES_4W", "F1", "Taux", unit, value, score, status, 4, orange, red, higherIsWorse, "d")

    @Test
    fun `indicator value carries its unit and a missing value is a dash`() {
        assertEquals("53 pb", formatIndicatorValue(53.0, "pb"))
        assertEquals("4,2 %", formatIndicatorValue(4.2, "%"))
        assertEquals("0,4", formatIndicatorValue(0.4, ""))
        assertEquals("—", formatIndicatorValue(null, "pb"))
    }

    @Test
    fun `thresholds read as at-least for higher-is-worse and at-most otherwise`() {
        assertEquals("orange ≥ 25 · rouge ≥ 50 pb", formatThresholds(indicator()))
        assertEquals(
            "orange ≤ -3 · rouge ≤ -5 %",
            formatThresholds(indicator(unit = "%", higherIsWorse = false, orange = -3.0, red = -5.0)),
        )
    }

    @Test
    fun `an indicator is live only when fresh AND scored, a stale or missing one is never presented as calm`() {
        assertTrue(isIndicatorLive(indicator()))
        assertFalse(isIndicatorLive(indicator(score = null)))
        assertFalse(isIndicatorLive(indicator(status = VigieIndicatorStatus.STALE)))
        assertFalse(isIndicatorLive(indicator(status = VigieIndicatorStatus.UNKNOWN)))

        assertEquals("inconnu", indicatorScoreLabel(null))
        assertEquals(VigieTone.NEUTRAL, indicatorTone(null))
        assertEquals(VigieTone.NORMAL, indicatorTone(0))
        assertEquals(VigieTone.VIGILANCE, indicatorTone(1))
        assertEquals(VigieTone.ALERTE, indicatorTone(2))
        assertEquals("périmé", indicatorStatusLabel(VigieIndicatorStatus.STALE))
        assertEquals("manquant", indicatorStatusLabel(VigieIndicatorStatus.MISSING))
        assertEquals("4 j", formatAgeDays(4))
        assertEquals("—", formatAgeDays(null))
    }

    @Test
    fun `indicators are grouped by family code`() {
        val vigie = normalWithRedF1().copy(
            indicators = listOf(
                indicator(),
                indicator().copy(code = "HY", family = "F3"),
            ),
        )

        assertEquals(listOf("RATES_4W"), indicatorsOf(vigie, "F1").map { it.code })
        assertEquals(listOf("HY"), indicatorsOf(vigie, "F3").map { it.code })
        assertTrue(indicatorsOf(vigie, "F2").isEmpty())
    }

    @Test
    fun `raw level note only when the raw level differs from the confirmed one`() {
        assertNull(rawLevelNote(normalWithRedF1()))
        val diverging = normalWithRedF1().copy(levelRaw = VigieLevel.ALERTE)
        assertEquals("Niveau brut du jour : Alerte — non encore confirmé", rawLevelNote(diverging))
        assertNull(rawLevelNote(normalWithRedF1().copy(levelRaw = VigieLevel.UNKNOWN)))
        assertNull(rawLevelNote(Vigie.UNAVAILABLE))
    }

    @Test
    fun `cutoffs line shows server thresholds only`() {
        assertEquals("Vigilance ≥ 2,4 · Alerte ≥ 3,75 · Crise ≥ 5,85", cutoffsLine(VigieCutoffs(2.4, 3.75, 5.85)))
        assertNull(cutoffsLine(null))
        assertNull(cutoffsLine(VigieCutoffs(null, null, null)))
    }

    @Test
    fun `refuge summary pluralises`() {
        assertEquals("2 jambes remplies sur 3 · 0 inconnue", refugeSummary(2, 3, 0))
        assertEquals("1 jambe remplie sur 3 · 2 inconnues", refugeSummary(1, 3, 2))
    }

    @Test
    fun `methodology mentions the version when known`() {
        assertTrue(methodologyText("1").endsWith("Méthodologie v1."))
        assertFalse(methodologyText(null).contains("Méthodologie v"))
    }
}
