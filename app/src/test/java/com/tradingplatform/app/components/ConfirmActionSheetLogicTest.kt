package com.tradingplatform.app.components

import com.tradingplatform.app.ui.components.ConfirmAction
import com.tradingplatform.app.ui.components.ConfirmStep
import com.tradingplatform.app.ui.components.canProceed
import com.tradingplatform.app.ui.components.displayedSummaryLines
import com.tradingplatform.app.ui.components.isReasonValid
import com.tradingplatform.app.ui.components.limitReasonInput
import com.tradingplatform.app.ui.components.primaryButtonDescription
import com.tradingplatform.app.ui.components.primaryButtonLabel
import com.tradingplatform.app.ui.components.reasonCounterLabel
import com.tradingplatform.app.ui.components.reasonToSubmit
import com.tradingplatform.app.ui.components.secondaryButtonLabel
import com.tradingplatform.app.ui.components.stepIndicatorLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Logique pure de la feuille de confirmation (aucune dépendance Compose/Android). */
class ConfirmActionSheetLogicTest {

    private val summary = listOf("Ordre" to "#42", "Symbole" to "AAPL")

    private fun action(requireReason: Boolean = false) = ConfirmAction(
        title = "Activer l'arrêt d'urgence ?",
        summaryLines = summary,
        confirmLabel = "Activer l'arrêt",
        destructive = true,
        requireReason = requireReason,
    )

    // ── isReasonValid / canProceed ──────────────────────────────────────────────────────────────

    @Test
    fun `reason is always valid when not required`() {
        assertTrue(isReasonValid("", requireReason = false))
        assertTrue(isReasonValid("   ", requireReason = false))
    }

    @Test
    fun `required reason rejects empty and blank text`() {
        assertFalse(isReasonValid("", requireReason = true))
        assertFalse(isReasonValid("   ", requireReason = true))
        assertFalse(isReasonValid("\n\t ", requireReason = true))
        assertTrue(isReasonValid("Arrêt manuel", requireReason = true))
        assertTrue(isReasonValid("  x ", requireReason = true))
    }

    @Test
    fun `summary step needs a valid reason only when one is required`() {
        assertTrue(canProceed(ConfirmStep.SUMMARY, reason = "", requireReason = false))
        assertFalse(canProceed(ConfirmStep.SUMMARY, reason = "", requireReason = true))
        assertFalse(canProceed(ConfirmStep.SUMMARY, reason = "   ", requireReason = true))
        assertTrue(canProceed(ConfirmStep.SUMMARY, reason = "Volatilité", requireReason = true))
    }

    @Test
    fun `biometric step is blocked while a prompt is already running`() {
        assertTrue(canProceed(ConfirmStep.BIOMETRIC, reason = "", requireReason = false, busy = false))
        assertFalse(canProceed(ConfirmStep.BIOMETRIC, reason = "", requireReason = false, busy = true))
        assertFalse(canProceed(ConfirmStep.BIOMETRIC, reason = "Volatilité", requireReason = true, busy = true))
    }

    @Test
    fun `biometric step still refuses a blank required reason`() {
        assertFalse(canProceed(ConfirmStep.BIOMETRIC, reason = " ", requireReason = true, busy = false))
    }

    // ── motif transmis ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `reason is trimmed when required and null otherwise`() {
        assertEquals("Arrêt manuel", reasonToSubmit("  Arrêt manuel \n", requireReason = true))
        assertNull(reasonToSubmit("ignoré", requireReason = false))
    }

    @Test
    fun `reason input is capped at 500 characters`() {
        val input = "a".repeat(650)

        val limited = limitReasonInput(input)

        assertEquals(500, limited.length)
        assertEquals(ConfirmAction.MAX_REASON_LENGTH, 500)
        assertEquals("court", limitReasonInput("court"))
    }

    // ── libellés ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `step indicator and button labels follow the step`() {
        assertEquals("Étape 1 sur 2 · Récapitulatif", stepIndicatorLabel(ConfirmStep.SUMMARY))
        assertEquals("Étape 2 sur 2 · Confirmation", stepIndicatorLabel(ConfirmStep.BIOMETRIC))

        assertEquals("Continuer", primaryButtonLabel(ConfirmStep.SUMMARY, "Annuler l'ordre"))
        assertEquals("Annuler l'ordre", primaryButtonLabel(ConfirmStep.BIOMETRIC, "Annuler l'ordre"))

        assertEquals("Annuler", secondaryButtonLabel(ConfirmStep.SUMMARY))
        assertEquals("Retour", secondaryButtonLabel(ConfirmStep.BIOMETRIC))
    }

    @Test
    fun `primary button description tells TalkBack what the tap does`() {
        assertEquals(
            "Continuer vers la confirmation",
            primaryButtonDescription(ConfirmStep.SUMMARY, "Annuler l'ordre"),
        )
        assertEquals(
            "Annuler l'ordre — confirmer avec l'empreinte",
            primaryButtonDescription(ConfirmStep.BIOMETRIC, "Annuler l'ordre"),
        )
    }

    @Test
    fun `reason counter shows the requirement and the length`() {
        assertEquals("Obligatoire · 0/500", reasonCounterLabel(""))
        assertEquals("Obligatoire · 12/500", reasonCounterLabel("Arrêt manuel"))
    }

    // ── lignes affichées ───────────────────────────────────────────────────────────────────────

    @Test
    fun `summary step shows only the caller lines`() {
        assertEquals(summary, displayedSummaryLines(action(requireReason = true), ConfirmStep.SUMMARY, "Motif saisi"))
    }

    @Test
    fun `biometric step appends the trimmed reason as the last line when one is required`() {
        val lines = displayedSummaryLines(action(requireReason = true), ConfirmStep.BIOMETRIC, "  Motif saisi ")

        assertEquals(summary + ("Motif" to "Motif saisi"), lines)
    }

    @Test
    fun `biometric step without a required reason shows the caller lines unchanged`() {
        assertEquals(summary, displayedSummaryLines(action(requireReason = false), ConfirmStep.BIOMETRIC, ""))
    }

    @Test
    fun `custom reason label is used in the recap line`() {
        val custom = action(requireReason = true).copy(reasonLabel = "Raison de l'arrêt")

        val lines = displayedSummaryLines(custom, ConfirmStep.BIOMETRIC, "Volatilité")

        assertEquals("Raison de l'arrêt" to "Volatilité", lines.last())
    }
}
