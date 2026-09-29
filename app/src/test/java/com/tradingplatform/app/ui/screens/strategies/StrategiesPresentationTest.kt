package com.tradingplatform.app.ui.screens.strategies

import com.tradingplatform.app.domain.exception.HttpStatusException
import com.tradingplatform.app.domain.model.PortfolioStrategyEntry
import com.tradingplatform.app.domain.model.WriteOutcome
import com.tradingplatform.app.vpn.VpnNotConnectedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

/**
 * Tests JVM de la logique de présentation pure de l'écran Stratégies : noms, libellés, descriptions
 * TalkBack, contenu de la confirmation (dont l'avertissement de réactivation) et messages de
 * résultat. Les attendus sont des valeurs littérales (jamais recalculées avec le code testé).
 */
class StrategiesPresentationTest {

    private fun entry(id: String, name: String?, active: Boolean) =
        PortfolioStrategyEntry(strategyId = id, name = name, isActive = active)

    // ── Nom affiché ───────────────────────────────────────────────────────────

    @Test
    fun `a known name is displayed trimmed`() {
        assertEquals("Momentum US", strategyDisplayName("  Momentum US ", "c2f4a9e0"))
    }

    @Test
    fun `a missing name falls back to Stratégie sans nom with the end of the id`() {
        assertEquals(
            "Stratégie sans nom (…2c3e4f)",
            strategyDisplayName(null, "c2f4a9e0-1b7d-4e6a-8f30-5d9a1b2c3e4f"),
        )
    }

    @Test
    fun `a blank name is treated as missing`() {
        assertEquals("Stratégie sans nom (…abc123)", strategyDisplayName("   ", "xyz-abc123"))
    }

    @Test
    fun `a short id is shown whole and an empty id gives the bare fallback`() {
        assertEquals("Stratégie sans nom (…s1)", strategyDisplayName(null, "s1"))
        assertEquals("Stratégie sans nom", strategyDisplayName(null, ""))
    }

    // ── États et actions ──────────────────────────────────────────────────────

    @Test
    fun `status and action labels`() {
        assertEquals("Active", linkStatusLabel(true))
        assertEquals("En pause", linkStatusLabel(false))
        assertEquals("Mettre en pause", linkActionLabel(true))
        assertEquals("Réactiver ce lien", linkActionLabel(false))
    }

    @Test
    fun `TalkBack descriptions name the strategy and its state`() {
        assertEquals("Stratégie Momentum US, active", strategyStatusDescription("Momentum US", true))
        assertEquals("Stratégie Momentum US, en pause", strategyStatusDescription("Momentum US", false))
        assertEquals(
            "Mettre en pause la stratégie Momentum US",
            strategyActionDescription("Momentum US", true),
        )
        assertEquals(
            "Réactiver le lien de la stratégie Momentum US",
            strategyActionDescription("Momentum US", false),
        )
    }

    @Test
    fun `summary counts active links over the total with French agreement`() {
        assertEquals("", strategiesSummary(emptyList()))
        assertEquals(
            "2 actives sur 3",
            strategiesSummary(listOf(entry("a", "A", true), entry("b", "B", false), entry("c", "C", true))),
        )
        assertEquals("1 active sur 2", strategiesSummary(listOf(entry("a", "A", true), entry("b", "B", false))))
        assertEquals("0 active sur 1", strategiesSummary(listOf(entry("a", "A", false))))
    }

    @Test
    fun `the detached-link hint appears only when a link is paused`() {
        assertFalse(shouldShowDetachedHint(emptyList()))
        assertFalse(shouldShowDetachedHint(listOf(entry("a", "A", true))))
        assertTrue(shouldShowDetachedHint(listOf(entry("a", "A", true), entry("b", "B", false))))
        assertEquals(
            "Un lien détaché sur le web apparaît aussi comme « en pause ».",
            DETACHED_LINK_HINT,
        )
    }

    @Test
    fun `the web editing reminder is fixed text`() {
        assertEquals("Édition des stratégies : sur le web", WEB_EDITING_NOTE)
    }

    @Test
    fun `busy labels`() {
        assertEquals("Envoi en cours…", strategyBusyLabel(StrategyWritePhase.SENDING))
        assertEquals("Vérification…", strategyBusyLabel(StrategyWritePhase.VERIFYING))
    }

    @Test
    fun `a write can start only when nothing is in flight or refreshing`() {
        val inFlight = StrategyWriteInFlight("p1", "s1", false, StrategyWritePhase.SENDING)
        assertTrue(canStartStrategyWrite(write = null, isRefreshing = false))
        assertFalse(canStartStrategyWrite(write = null, isRefreshing = true))
        assertFalse(canStartStrategyWrite(write = inFlight, isRefreshing = false))
        assertFalse(canStartStrategyWrite(write = inFlight.copy(phase = StrategyWritePhase.VERIFYING), isRefreshing = false))
    }

    // ── Confirmation ──────────────────────────────────────────────────────────

    @Test
    fun `pause confirmation recaps strategy portfolio and effect without a reason`() {
        val action = strategyConfirmAction("Momentum US", "PEA Long terme", targetActive = false)

        assertEquals("Mettre cette stratégie en pause ?", action.title)
        assertEquals("Mettre en pause", action.confirmLabel)
        assertEquals(
            listOf(
                "Stratégie" to "Momentum US",
                "Portefeuille" to "PEA Long terme",
            ),
            action.summaryLines,
        )
        assertEquals(
            "Les nouveaux signaux de cette stratégie ne passeront plus d'ordres pour ce portefeuille.",
            action.message,
        )
        assertFalse(action.requireReason)
        assertFalse(action.destructive)
    }

    @Test
    fun `reactivation confirmation carries the mandatory detached-link warning`() {
        val action = strategyConfirmAction("Momentum US", "PEA Long terme", targetActive = true)

        assertEquals("Réactiver ce lien ?", action.title)
        assertEquals("Réactiver ce lien", action.confirmLabel)
        assertEquals(
            listOf(
                "Stratégie" to "Momentum US",
                "Portefeuille" to "PEA Long terme",
            ),
            action.summaryLines,
        )
        assertEquals(
            "Les nouveaux signaux de cette stratégie pourront de nouveau passer des ordres pour ce portefeuille. " +
                "Un lien détaché sur le web est indiscernable d'un lien en pause : " +
                "la réactivation peut le remettre en service.",
            action.message,
        )
        assertFalse(action.requireReason)
        assertFalse(action.destructive)
    }

    // ── Messages de résultat ──────────────────────────────────────────────────

    @Test
    fun `success message says effectuée on a 2xx and demandée when unconfirmed`() {
        assertEquals("Mise en pause effectuée", strategyWriteSuccessMessage(false, WriteOutcome.CONFIRMED))
        assertEquals(
            "Mise en pause demandée — vérification en cours",
            strategyWriteSuccessMessage(false, WriteOutcome.REQUESTED_UNCONFIRMED),
        )
        assertEquals("Réactivation effectuée", strategyWriteSuccessMessage(true, WriteOutcome.CONFIRMED))
        assertEquals(
            "Réactivation demandée — vérification en cours",
            strategyWriteSuccessMessage(true, WriteOutcome.REQUESTED_UNCONFIRMED),
        )
    }

    @Test
    fun `verification stays silent when a confirmed write matches the re-read state`() {
        assertNull(strategyVerificationMessage(false, false, WriteOutcome.CONFIRMED))
        assertNull(strategyVerificationMessage(true, true, WriteOutcome.CONFIRMED))
    }

    @Test
    fun `verification confirms an unconfirmed write once the server shows the requested state`() {
        assertEquals(
            "Confirmé : le lien est en pause.",
            strategyVerificationMessage(false, false, WriteOutcome.REQUESTED_UNCONFIRMED),
        )
        assertEquals(
            "Confirmé : le lien est de nouveau actif.",
            strategyVerificationMessage(true, true, WriteOutcome.REQUESTED_UNCONFIRMED),
        )
    }

    @Test
    fun `verification tells the user when the server state differs from the request`() {
        assertEquals(
            "La demande n'apparaît pas appliquée : le lien est toujours actif.",
            strategyVerificationMessage(false, true, WriteOutcome.REQUESTED_UNCONFIRMED),
        )
        assertEquals(
            "La demande n'apparaît pas appliquée : le lien est toujours en pause.",
            strategyVerificationMessage(true, false, WriteOutcome.CONFIRMED),
        )
    }

    @Test
    fun `verification reports a link that disappeared`() {
        assertEquals(
            "Ce lien n'apparaît plus dans la liste — actualisez l'écran.",
            strategyVerificationMessage(false, null, WriteOutcome.CONFIRMED),
        )
    }

    // ── Échecs ────────────────────────────────────────────────────────────────

    @Test
    fun `VPN block gives the write-gate VPN message, also when wrapped`() {
        assertEquals(
            "VPN requis — activez le tunnel puis réessayez.",
            strategyWriteFailureMessage(VpnNotConnectedException()),
        )
        assertEquals(
            "VPN requis — activez le tunnel puis réessayez.",
            strategyWriteFailureMessage(IOException("canceled", VpnNotConnectedException())),
        )
    }

    @Test
    fun `HTTP failures map to explicit French messages`() {
        val endpoint = "v1/portfolios/{portfolio_id}/strategies/{strategy_id}"
        assertEquals(
            "Session expirée — reconnectez-vous.",
            strategyWriteFailureMessage(HttpStatusException(401, endpoint)),
        )
        assertEquals(
            "Action refusée par le serveur (droits insuffisants).",
            strategyWriteFailureMessage(HttpStatusException(403, endpoint)),
        )
        assertEquals(
            "Lien introuvable — il a peut-être été supprimé sur le web. Actualisez l'écran.",
            strategyWriteFailureMessage(HttpStatusException(404, endpoint)),
        )
        assertEquals(
            "Conflit : l'état de ce lien a changé — actualisez avant de réessayer.",
            strategyWriteFailureMessage(HttpStatusException(409, endpoint)),
        )
        assertEquals(
            "Demande refusée par le serveur — vérifiez les allocations sur le web.",
            strategyWriteFailureMessage(HttpStatusException(422, endpoint)),
        )
        assertEquals(
            "Trop de demandes — patientez avant de réessayer.",
            strategyWriteFailureMessage(HttpStatusException(429, endpoint)),
        )
        assertEquals(
            "Échec de la demande (erreur HTTP 400) — actualisez l'écran pour vérifier l'état.",
            strategyWriteFailureMessage(HttpStatusException(400, endpoint)),
        )
    }

    @Test
    fun `other failures never leak technical details`() {
        assertEquals(
            "Serveur injoignable — la demande n'a pas été envoyée.",
            strategyWriteFailureMessage(UnknownHostException("10.42.0.1")),
        )
        assertEquals(
            "Échec de la demande — actualisez l'écran pour vérifier l'état.",
            strategyWriteFailureMessage(IllegalStateException("boom secret-detail")),
        )
    }

    @Test
    fun `load errors distinguish the VPN from other failures`() {
        assertEquals(
            "VPN requis — activez le tunnel pour charger les stratégies.",
            strategiesLoadErrorMessage(VpnNotConnectedException()),
        )
        assertEquals(
            "Impossible de charger les stratégies — tirez pour réessayer.",
            strategiesLoadErrorMessage(IllegalStateException("List portfolio strategies failed: HTTP 500")),
        )
    }
}
