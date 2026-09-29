package com.tradingplatform.app.ui.screens.settings

import com.tradingplatform.app.domain.exception.HttpStatusException
import com.tradingplatform.app.domain.model.NotifCategory
import com.tradingplatform.app.domain.model.QuietHours
import com.tradingplatform.app.domain.model.RiskAlertThresholds
import com.tradingplatform.app.vpn.VpnNotConnectedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class NotificationPrefsPresentationTest {

    @Test
    fun `category titles`() {
        assertEquals("Signaux de stratégie", categoryTitle(NotifCategory.STRATEGY_SIGNAL))
        assertEquals("Alertes de risque", categoryTitle(NotifCategory.RISK_ALERT))
        assertEquals("Système", categoryTitle(NotifCategory.SYSTEM))
    }

    @Test
    fun `every category has a description`() {
        NotifCategory.entries.forEach { assertTrue(categoryDescription(it).isNotBlank()) }
    }

    @Test
    fun `push state label`() {
        assertEquals("Activée", pushStateLabel(true))
        assertEquals("Désactivée", pushStateLabel(false))
    }

    @Test
    fun `confirm action for each direction`() {
        val disable = pushChangeConfirmAction(NotifCategory.STRATEGY_SIGNAL, enabled = false)
        assertEquals("Désactiver les notifications push ?", disable.title)
        assertEquals("Désactiver le push", disable.confirmLabel)
        assertEquals("Signaux de stratégie", disable.summaryLines[0].second)
        assertEquals("Désactivée", disable.summaryLines[1].second)

        val enable = pushChangeConfirmAction(NotifCategory.SYSTEM, enabled = true)
        assertEquals("Activer les notifications push ?", enable.title)
        assertEquals("Activer le push", enable.confirmLabel)
        assertEquals("Activée", enable.summaryLines[1].second)
    }

    @Test
    fun `outcome message reports the re-read state`() {
        assertEquals(
            "Notifications push activées : Système.",
            pushOutcomeMessage(NotifCategory.SYSTEM, requestedEnabled = true, rereadEnabled = true),
        )
        assertEquals(
            "Notifications push désactivées : Signaux de stratégie.",
            pushOutcomeMessage(NotifCategory.STRATEGY_SIGNAL, requestedEnabled = false, rereadEnabled = false),
        )
        assertEquals(
            "Modification demandée, mais le serveur affiche encore l'état précédent. " +
                "Actualisez avant de réessayer.",
            pushOutcomeMessage(NotifCategory.SYSTEM, requestedEnabled = true, rereadEnabled = false),
        )
        assertEquals(
            "Modification demandée — relecture impossible. Actualisez pour vérifier avant de réessayer.",
            pushOutcomeMessage(NotifCategory.SYSTEM, requestedEnabled = true, rereadEnabled = null),
        )
    }

    @Test
    fun `failure messages are explicit per cause`() {
        assertEquals("VPN requis — activez le tunnel puis réessayez.", pushFailureMessage(VpnNotConnectedException()))
        assertEquals("Session expirée — reconnectez-vous.", pushFailureMessage(HttpStatusException(401, "e")))
        assertEquals(
            "Préférences modifiées entre-temps — réessayez",
            pushFailureMessage(HttpStatusException(409, "e", "Préférences modifiées entre-temps — réessayez")),
        )
        assertEquals(
            "Modification refusée par le serveur (HTTP 403) — préférence inchangée.",
            pushFailureMessage(HttpStatusException(403, "e")),
        )
        assertEquals(
            "Modification impossible pour le moment. Actualisez pour vérifier l'état avant de réessayer.",
            pushFailureMessage(IllegalStateException("boom")),
        )
    }

    @Test
    fun `load error messages`() {
        assertEquals("VPN requis — activez le tunnel puis réessayez.", prefsLoadErrorMessage(VpnNotConnectedException()))
        assertEquals(
            "Le serveur a répondu par une erreur (HTTP 503).",
            prefsLoadErrorMessage(HttpStatusException(503, "e")),
        )
        assertEquals(
            "Serveur injoignable — vérifiez la connexion puis réessayez.",
            prefsLoadErrorMessage(IOException("timeout")),
        )
        assertEquals("Lecture des préférences impossible pour le moment.", prefsLoadErrorMessage(IllegalStateException()))
    }

    @Test
    fun `quiet hours summary`() {
        assertEquals("Désactivées", quietHoursSummary(QuietHours(enabled = false, start = "22:00", end = "08:00")))
        assertEquals(
            "Actives de 22:00 à 08:00",
            quietHoursSummary(QuietHours(enabled = true, start = "22:00", end = "08:00")),
        )
    }

    @Test
    fun `threshold percent drops useless zeros and uses a french comma`() {
        assertEquals("10%", formatThresholdPercent(0.10))
        assertEquals("12,5%", formatThresholdPercent(0.125))
        assertEquals("50%", formatThresholdPercent(0.5))
        assertEquals("100%", formatThresholdPercent(1.0))
        assertEquals("3,3%", formatThresholdPercent(0.0333))
        assertEquals("0%", formatThresholdPercent(0.0))
    }

    @Test
    fun `threshold lines list only the configured thresholds`() {
        assertEquals(emptyList<Pair<String, String>>(), thresholdLines(RiskAlertThresholds()))

        assertEquals(
            listOf(
                "VaR (part du maximum)" to "80%",
                "Alerte de drawdown" to "10%",
                "Concentration d'une position" to "60%",
                "Signaux ignorés par jour" to "25",
            ),
            thresholdLines(
                RiskAlertThresholds(
                    varPctOfMax = 0.8,
                    suppressedSignalsPerDay = 25,
                    drawdownWarnPct = 0.1,
                    positionConcentrationPct = 0.6,
                ),
            ),
        )

        assertEquals(
            listOf("Alerte de drawdown" to "12,5%"),
            thresholdLines(RiskAlertThresholds(drawdownWarnPct = 0.125)),
        )
    }
}
