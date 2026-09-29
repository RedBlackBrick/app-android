package com.tradingplatform.app.ui.screens.alerts

import com.tradingplatform.app.domain.model.InboxNotification
import com.tradingplatform.app.vpn.VpnNotConnectedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant

class InboxPresentationTest {

    private fun notif(
        type: String = "risk_alert",
        title: String? = "Drawdown à 12,4 %",
        createdAt: Instant = Instant.parse("2026-09-29T08:14:03Z"),
    ) = InboxNotification(
        id = "n1",
        type = type,
        title = title,
        body = "Corps",
        read = false,
        createdAt = createdAt,
    )

    // ── Catégorie ──────────────────────────────────────────────────────────────

    @Test
    fun `risk_alert is the risk category`() {
        assertEquals(InboxCategory.RISK, inboxCategory("risk_alert"))
    }

    @Test
    fun `strategy related types are the strategy category`() {
        listOf(
            "signal_blocked",
            "strategy_degradation",
            "strategy_version_available",
            "catalyst_event",
            "spinoff_approaching",
        ).forEach { type ->
            assertEquals("type=$type", InboxCategory.STRATEGY, inboxCategory(type))
        }
    }

    @Test
    fun `system and device types are the system category`() {
        listOf(
            "system",
            "network_degradation",
            "device_offline",
            "device_online",
            "device_unpaired",
            "device_paired",
        ).forEach { type ->
            assertEquals("type=$type", InboxCategory.SYSTEM, inboxCategory(type))
        }
    }

    @Test
    fun `unknown legacy and blank types are the generic category`() {
        listOf("trade_executed", "price_alert", "quelque_chose_de_nouveau", "", "   ").forEach { type ->
            assertEquals("type='$type'", InboxCategory.GENERIC, inboxCategory(type))
        }
    }

    @Test
    fun `category ignores case and surrounding spaces`() {
        assertEquals(InboxCategory.RISK, inboxCategory("  RISK_ALERT "))
    }

    // ── Libellé ────────────────────────────────────────────────────────────────

    @Test
    fun `the 12 known types have their own French label`() {
        assertEquals("Risque", inboxTypeLabel("risk_alert"))
        assertEquals("Système", inboxTypeLabel("system"))
        assertEquals("Réseau", inboxTypeLabel("network_degradation"))
        assertEquals("Appareil hors ligne", inboxTypeLabel("device_offline"))
        assertEquals("Appareil en ligne", inboxTypeLabel("device_online"))
        assertEquals("Appareil désappairé", inboxTypeLabel("device_unpaired"))
        assertEquals("Appareil appairé", inboxTypeLabel("device_paired"))
        assertEquals("Signal bloqué", inboxTypeLabel("signal_blocked"))
        assertEquals("Stratégie", inboxTypeLabel("strategy_degradation"))
        assertEquals("Nouvelle version", inboxTypeLabel("strategy_version_available"))
        assertEquals("Catalyseur", inboxTypeLabel("catalyst_event"))
        assertEquals("Spin-off", inboxTypeLabel("spinoff_approaching"))
    }

    @Test
    fun `unknown type gets the generic label`() {
        assertEquals("Notification", inboxTypeLabel("quelque_chose_de_nouveau"))
        assertEquals("Notification", inboxTypeLabel(""))
    }

    // ── Titre ──────────────────────────────────────────────────────────────────

    @Test
    fun `title is the server title when present`() {
        assertEquals("Drawdown à 12,4 %", inboxTitle(notif(title = "  Drawdown à 12,4 %  ")))
    }

    @Test
    fun `missing or blank title falls back to the type label`() {
        assertEquals("Risque", inboxTitle(notif(type = "risk_alert", title = null)))
        assertEquals("Signal bloqué", inboxTitle(notif(type = "signal_blocked", title = "   ")))
        assertEquals("Notification", inboxTitle(notif(type = "inconnu", title = null)))
    }

    // ── Horodatage ─────────────────────────────────────────────────────────────

    @Test
    fun `epoch means no timestamp`() {
        assertFalse(inboxHasTimestamp(notif(createdAt = Instant.EPOCH)))
        assertTrue(inboxHasTimestamp(notif(createdAt = Instant.parse("2026-09-29T08:14:03Z"))))
    }

    // ── Échec de chargement ────────────────────────────────────────────────────

    @Test
    fun `VPN not connected is tested before IOException`() {
        // VpnNotConnectedException étend IOException : ne doit PAS donner « serveur injoignable »
        val vpnFailure: Throwable = VpnNotConnectedException()
        assertTrue(vpnFailure is IOException)
        assertEquals(InboxUiState.VpnRequired, inboxFailureState(vpnFailure))
    }

    @Test
    fun `wrapped VPN not connected is still recognised`() {
        val wrapped = RuntimeException("wrapper", VpnNotConnectedException())
        assertEquals(InboxUiState.VpnRequired, inboxFailureState(wrapped))
    }

    @Test
    fun `IOException and timeouts are unreachable`() {
        val expected = InboxUiState.Error("Serveur injoignable. Vérifiez la connexion puis réessayez.")
        assertEquals(expected, inboxFailureState(IOException("reset")))
        assertEquals(expected, inboxFailureState(SocketTimeoutException("timeout")))
        assertEquals(expected, inboxFailureState(IllegalStateException("x", IOException("cause"))))
    }

    @Test
    fun `any other failure is the generic error`() {
        val expected = InboxUiState.Error("Impossible de charger les notifications du serveur.")
        assertEquals(expected, inboxFailureState(RuntimeException("HTTP 500")))
        assertEquals(expected, inboxFailureState(IllegalStateException("Empty notifications response")))
    }
}
