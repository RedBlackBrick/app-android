package com.tradingplatform.app.ui.screens.risk

import com.tradingplatform.app.domain.exception.HttpStatusException
import com.tradingplatform.app.domain.model.RiskStatus
import com.tradingplatform.app.vpn.VpnNotConnectedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RiskPresentationTest {

    private val base = RiskStatus(
        killSwitchActive = false,
        killSwitchReason = null,
        unresolvedViolations = 0,
        dailyLossUsagePct = null,
        drawdownCurrentPct = null,
    )

    // ── Kill switch ──────────────────────────────────────────────────────────

    @Test
    fun `headline distinguishes active, inactive and unverified kill switch`() {
        assertEquals(
            "Trading suspendu — kill switch actif",
            killSwitchHeadline(base.copy(killSwitchActive = true)),
        )
        assertEquals("Aucun kill switch actif", killSwitchHeadline(base))
        // Lecture partielle sans kill switch détecté : on ne prétend pas qu'il est inactif.
        assertEquals(
            "Kill switch non vérifié — données partielles",
            killSwitchHeadline(base.copy(isPartial = true)),
        )
        // Un kill switch détecté prime sur la mention « partielle ».
        assertEquals(
            "Trading suspendu — kill switch actif",
            killSwitchHeadline(base.copy(killSwitchActive = true, isPartial = true)),
        )
    }

    @Test
    fun `reason label is trimmed and absent when blank`() {
        assertEquals("Motif : Perte anormale", killSwitchReasonLabel("  Perte anormale "))
        assertNull(killSwitchReasonLabel(null))
        assertNull(killSwitchReasonLabel("   "))
    }

    @Test
    fun `confirm action states what the kill switch does and does not do`() {
        val action = killSwitchConfirmAction("Growth EUR")

        assertEquals("Suspendre le trading ?", action.title)
        assertTrue(action.destructive)
        assertTrue(action.requireReason)
        assertEquals("Motif", action.reasonLabel)
        assertEquals(
            listOf(
                "Portefeuille" to "Growth EUR",
                "Effet" to "Nouveaux ordres bloqués pendant 7 jours",
                "Ordres ouverts" to "Non annulés",
                "Sorties" to "Non bloquées (stops, clôtures)",
                "Levée" to "Sur le web uniquement",
            ),
            action.summaryLines,
        )
    }

    @Test
    fun `confirm action falls back to a generic portfolio label when the name is unknown`() {
        assertEquals("Portefeuille actif", killSwitchConfirmAction(null).summaryLines.first().second)
        assertEquals("Portefeuille actif", killSwitchConfirmAction("  ").summaryLines.first().second)
    }

    @Test
    fun `outcome message follows the re-read state only`() {
        assertEquals("Suspension du trading confirmée — kill switch actif.", killSwitchOutcomeMessage(true))
        assertEquals(
            "Suspension demandée — non confirmée par le serveur. Actualisez avant de réessayer.",
            killSwitchOutcomeMessage(false),
        )
        assertEquals(
            "Suspension demandée — relecture impossible. Actualisez pour vérifier l'état avant de réessayer.",
            killSwitchOutcomeMessage(null),
        )
    }

    @Test
    fun `other portfolio message names the portfolio when known`() {
        assertEquals(
            "Suspension demandée pour « Growth EUR » — vérifiez l'état de ce portefeuille.",
            killSwitchOtherPortfolioMessage("Growth EUR"),
        )
        assertEquals(
            "Suspension demandée pour l'autre portefeuille — vérifiez l'état de ce portefeuille.",
            killSwitchOtherPortfolioMessage(null),
        )
    }

    @Test
    fun `failure messages are explicit per cause`() {
        assertEquals(
            "VPN requis — activez le tunnel puis réessayez.",
            killSwitchFailureMessage(VpnNotConnectedException()),
        )
        assertEquals(
            "Motif invalide — saisissez un motif de 1 à 500 caractères.",
            killSwitchFailureMessage(IllegalArgumentException("x")),
        )
        assertEquals("Session expirée — reconnectez-vous.", killSwitchFailureMessage(HttpStatusException(401, "e")))
        assertEquals(
            "Suspension refusée — ce portefeuille n'est pas accessible avec ce compte.",
            killSwitchFailureMessage(HttpStatusException(403, "e")),
        )
        assertEquals(
            "Conflit — relisez l'état",
            killSwitchFailureMessage(HttpStatusException(409, "e", "Conflit — relisez l'état")),
        )
        assertEquals(
            "Suspension refusée par le serveur (HTTP 422) — aucun kill switch activé.",
            killSwitchFailureMessage(HttpStatusException(422, "e")),
        )
        assertEquals(
            "Suspension impossible pour le moment. Actualisez pour vérifier l'état avant de réessayer.",
            killSwitchFailureMessage(IllegalStateException("boom")),
        )
    }

    @Test
    fun `load error messages`() {
        assertEquals("VPN requis — activez le tunnel puis réessayez.", riskLoadErrorMessage(VpnNotConnectedException()))
        assertEquals(
            "Le serveur a répondu par une erreur (HTTP 500).",
            riskLoadErrorMessage(HttpStatusException(500, "e")),
        )
        assertEquals(
            "Serveur injoignable — vérifiez la connexion puis réessayez.",
            riskLoadErrorMessage(IOException("timeout")),
        )
        assertEquals("Lecture du risque impossible pour le moment.", riskLoadErrorMessage(IllegalStateException()))
    }

    // ── Violations ───────────────────────────────────────────────────────────

    @Test
    fun `violations value label and level`() {
        assertEquals("0", violationsValue(0))
        assertEquals("3", violationsValue(3))
        assertEquals("100+", violationsValue(100))
        assertEquals("100+", violationsValue(250))

        assertEquals("Aucune violation non résolue", violationsLabel(0))
        assertEquals("1 violation non résolue", violationsLabel(1))
        assertEquals("3 violations non résolues", violationsLabel(3))
        assertEquals("100 violations non résolues ou plus", violationsLabel(100))

        assertEquals(RiskLevel.OK, violationsLevel(0))
        assertEquals(RiskLevel.WARNING, violationsLevel(1))
    }

    // ── Perte du jour ────────────────────────────────────────────────────────

    @Test
    fun `daily loss level thresholds follow the displayed rounded percent`() {
        assertEquals(RiskLevel.UNKNOWN, dailyLossLevel(null))
        assertEquals(RiskLevel.UNKNOWN, dailyLossLevel(Double.NaN))
        assertEquals(RiskLevel.OK, dailyLossLevel(0.0))
        assertEquals(RiskLevel.OK, dailyLossLevel(0.79))
        assertEquals(RiskLevel.WARNING, dailyLossLevel(0.80))
        assertEquals(RiskLevel.WARNING, dailyLossLevel(0.99))
        assertEquals(RiskLevel.CRITICAL, dailyLossLevel(1.0))
        assertEquals(RiskLevel.CRITICAL, dailyLossLevel(1.6))
        // 79,5 % s'affiche « 80% » : le niveau doit être celui de ce qui est affiché.
        assertEquals("80%", dailyLossValue(0.795))
        assertEquals(RiskLevel.WARNING, dailyLossLevel(0.795))
    }

    @Test
    fun `daily loss value and progress`() {
        assertEquals("85%", dailyLossValue(0.85))
        assertEquals("0%", dailyLossValue(0.0))
        assertEquals("160%", dailyLossValue(1.6))
        assertEquals("—", dailyLossValue(null))

        assertEquals(0.85f, dailyLossProgress(0.85), 0.0001f)
        assertEquals(1f, dailyLossProgress(1.6), 0f)
        assertEquals(0f, dailyLossProgress(-0.3), 0f)
        assertEquals(0f, dailyLossProgress(null), 0f)
    }

    @Test
    fun `daily loss spoken description carries the level in words`() {
        assertEquals(
            "Perte du jour : 85 pour cent de la limite. Proche de la limite",
            dailyLossSpoken(0.85),
        )
        assertEquals(
            "Perte du jour : 100 pour cent de la limite. Limite atteinte",
            dailyLossSpoken(1.0),
        )
        assertEquals("Perte du jour : limite non disponible", dailyLossSpoken(null))
    }

    // ── Drawdown ─────────────────────────────────────────────────────────────

    @Test
    fun `drawdown is shown as a signed french percent`() {
        assertEquals("-12,40%", drawdownValue(-0.124))
        assertEquals("0,00%", drawdownValue(0.0))
        assertEquals("—", drawdownValue(null))
        assertEquals("Drawdown courant : moins 12,40 pour cent", drawdownSpoken(-0.124))
        assertEquals("Drawdown courant : indisponible", drawdownSpoken(null))
    }
}
