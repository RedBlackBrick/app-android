package com.tradingplatform.app.widget

import com.tradingplatform.app.data.local.db.CacheTtl
import com.tradingplatform.app.data.local.db.entity.VigieSnapshotEntity
import com.tradingplatform.app.ui.screens.vigie.VigieTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contenu du widget Vigie dérivé de la ligne Room : jamais de vert sans donnée, sync toujours datée. */
class VigieWidgetModelTest {

    private val now = 1_800_000_000_000L

    private fun entity(
        level: String = "normal",
        score: Double? = 1.25,
        family: String? = "F1",
        familyScore: Double? = 1.9,
        refuge: String = "none",
        isStale: Boolean = false,
        syncedAt: Long = now - 5 * 60_000L,
    ) = VigieSnapshotEntity(
        asOf = "2026-10-06",
        computedAt = now - 3_600_000L,
        score = score,
        level = level,
        levelRaw = level,
        refugeState = refuge,
        coverage = 1.0,
        liveIndicators = 14,
        expectedIndicators = 14,
        mostStressedFamilyCode = family,
        mostStressedFamilyLabel = family?.let { "Taux et refuge obligataire" },
        mostStressedFamilyScore = familyScore,
        isStale = isStale,
        payloadJson = "{}",
        syncedAt = syncedAt,
    )

    @Test
    fun `no row is Pas encore de donnee, neutral, with no score, never a level`() {
        val model = vigieWidgetModel(null, now)

        assertFalse(model.hasData)
        assertEquals("Pas encore de donnée", model.levelLabel)
        assertEquals(VigieTone.NEUTRAL, model.tone)
        assertEquals("—", model.scoreText)
        assertNull(model.syncText)
        assertNull(model.stressedText)
    }

    @Test
    fun `a normal row shows score, level, abbreviated stressed family, refuge and a sync label`() {
        val model = vigieWidgetModel(entity(), now)

        assertTrue(model.hasData)
        assertEquals("1,3", model.scoreText)
        assertEquals("Normal", model.levelLabel)
        assertEquals(VigieTone.NORMAL, model.tone)
        assertEquals("Tendue : F1", model.stressedText)
        assertEquals("Refuge : aucun", model.refugeText)
        assertEquals("Sync il y a 5min", model.syncText)
        assertFalse(model.isStale)
    }

    @Test
    fun `a Normal global with a red F1 keeps F1 visible and emphasised`() {
        val model = vigieWidgetModel(entity(level = "normal", familyScore = 1.9), now)

        assertEquals("Tendue : F1", model.stressedText)
        assertTrue(model.masksStress)
    }

    @Test
    fun `an unknown or unrecognised level is neutral Donnees insuffisantes with no score, never green`() {
        listOf("unknown", "bizarre", "").forEach { level ->
            val model = vigieWidgetModel(entity(level = level, score = 2.0), now)

            assertEquals("Données insuffisantes", model.levelLabel)
            assertEquals(VigieTone.NEUTRAL, model.tone)
            assertEquals("—", model.scoreText)
            assertFalse(model.masksStress)
        }
    }

    @Test
    fun `sync older than the Vigie TTL gets the peremption badge`() {
        val old = now - CacheTtl.VIGIE_MS - 60_000L

        val model = vigieWidgetModel(entity(syncedAt = old), now)

        assertTrue(model.isStale)
        assertTrue(model.syncText!!.startsWith("périmé · "))
    }

    @Test
    fun `a sync inside the Vigie TTL but older than the 20 min grace is NOT stale (6 h freshness)`() {
        val model = vigieWidgetModel(entity(syncedAt = now - 2 * 3_600_000L), now)

        assertFalse(model.isStale)
        assertTrue(model.syncText!!.startsWith("Sync "))
    }

    @Test
    fun `server is_stale marks the widget stale even when the sync is recent`() {
        val model = vigieWidgetModel(entity(isStale = true), now)

        assertTrue(model.isStale)
        assertEquals("périmé · il y a 5min", model.syncText)
    }

    @Test
    fun `a stale old sync is not double prefixed when the server also says stale`() {
        val model = vigieWidgetModel(entity(isStale = true, syncedAt = now - CacheTtl.VIGIE_MS - 1L), now)

        assertEquals(1, Regex("périmé").findAll(model.syncText!!).count())
    }

    @Test
    fun `missing stressed family and unknown refuge are shown honestly`() {
        val model = vigieWidgetModel(entity(family = null, familyScore = null, refuge = "unknown"), now)

        assertNull(model.stressedText)
        assertFalse(model.masksStress)
        assertEquals("Refuge : inconnu", model.refugeText)
    }

    @Test
    fun `every Vigie tone maps to a widget colour`() {
        assertEquals(WidgetColors.Neutral, VigieTone.NEUTRAL.widgetTone())
        assertEquals(WidgetColors.Positive, VigieTone.NORMAL.widgetTone())
        assertEquals(WidgetColors.Warning, VigieTone.VIGILANCE.widgetTone())
        assertEquals(WidgetColors.Negative, VigieTone.ALERTE.widgetTone())
        assertEquals(WidgetColors.Crisis, VigieTone.CRISE.widgetTone())
    }
}
