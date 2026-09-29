package com.tradingplatform.app.ui.screens.devices

import com.tradingplatform.app.domain.model.BrokerGatewayStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mapping des statuts de la broker gateway et des connexions broker.
 *
 * Les valeurs d'entrée sont le vocabulaire RÉEL du backend, écrites en littéral : un test qui
 * relirait les constantes du code ne détecterait pas une dérive de vocabulaire.
 */
class DeviceStatusDisplayTest {

    // ── gatewayStatusDisplay — vocabulaire backend : stopped / starting / running / error / configured ──

    @Test
    fun `running is a success`() {
        assertEquals(StatusDisplay("En marche", StatusTone.SUCCESS), gatewayStatusDisplay("running"))
    }

    @Test
    fun `starting is a warning`() {
        assertEquals(StatusDisplay("Démarrage", StatusTone.WARNING), gatewayStatusDisplay("starting"))
    }

    @Test
    fun `error is an error`() {
        assertEquals(StatusDisplay("Erreur", StatusTone.ERROR), gatewayStatusDisplay("error"))
    }

    @Test
    fun `stopped is neutral`() {
        assertEquals(StatusDisplay("Arrêtée", StatusTone.NEUTRAL), gatewayStatusDisplay("stopped"))
    }

    @Test
    fun `configured is neutral`() {
        assertEquals(StatusDisplay("Configurée", StatusTone.NEUTRAL), gatewayStatusDisplay("configured"))
    }

    @Test
    fun `gateway status is matched case-insensitively and ignores surrounding spaces`() {
        assertEquals(StatusTone.SUCCESS, gatewayStatusDisplay("RUNNING").tone)
        assertEquals(StatusTone.WARNING, gatewayStatusDisplay(" Starting ").tone)
        assertEquals(StatusTone.ERROR, gatewayStatusDisplay("Error").tone)
    }

    @Test
    fun `former connected and active values are no longer treated as success for a gateway`() {
        // L'ancien écran attendait « connected/active » : le backend n'envoie jamais ces valeurs.
        assertEquals(StatusTone.NEUTRAL, gatewayStatusDisplay("connected").tone)
        assertEquals(StatusTone.NEUTRAL, gatewayStatusDisplay("active").tone)
    }

    @Test
    fun `unknown gateway status stays neutral and remains visible`() {
        assertEquals(StatusDisplay("Degraded", StatusTone.NEUTRAL), gatewayStatusDisplay("degraded"))
    }

    @Test
    fun `blank gateway status is shown as unknown`() {
        assertEquals(StatusDisplay("Inconnu", StatusTone.NEUTRAL), gatewayStatusDisplay(""))
        assertEquals(StatusDisplay("Inconnu", StatusTone.NEUTRAL), gatewayStatusDisplay("   "))
    }

    // ── brokerGatewayDisplay ─────────────────────────────────────────────────

    @Test
    fun `missing gateway is not configured`() {
        assertEquals(StatusDisplay("Non configurée", StatusTone.NEUTRAL), brokerGatewayDisplay(null))
    }

    @Test
    fun `disabled gateway is shown as disabled whatever its raw status`() {
        val gateway = BrokerGatewayStatus(enabled = false, status = "running", brokerId = 1)

        assertEquals(StatusDisplay("Désactivée", StatusTone.NEUTRAL), brokerGatewayDisplay(gateway))
    }

    @Test
    fun `enabled gateway uses the mapped status`() {
        assertEquals(
            StatusDisplay("En marche", StatusTone.SUCCESS),
            brokerGatewayDisplay(BrokerGatewayStatus(enabled = true, status = "running", brokerId = 1)),
        )
        assertEquals(
            StatusDisplay("Erreur", StatusTone.ERROR),
            brokerGatewayDisplay(BrokerGatewayStatus(enabled = true, status = "error", brokerId = null)),
        )
        assertEquals(
            StatusDisplay("Démarrage", StatusTone.WARNING),
            brokerGatewayDisplay(BrokerGatewayStatus(enabled = true, status = "starting", brokerId = 2)),
        )
    }

    // ── brokerConnectionDisplay ──────────────────────────────────────────────

    @Test
    fun `connected and active connections are a success`() {
        assertEquals(StatusDisplay("Connecté", StatusTone.SUCCESS), brokerConnectionDisplay("connected"))
        assertEquals(StatusDisplay("Actif", StatusTone.SUCCESS), brokerConnectionDisplay("active"))
    }

    @Test
    fun `disconnected inactive and error connections are an error`() {
        assertEquals(StatusDisplay("Déconnecté", StatusTone.ERROR), brokerConnectionDisplay("disconnected"))
        assertEquals(StatusDisplay("Inactif", StatusTone.ERROR), brokerConnectionDisplay("inactive"))
        assertEquals(StatusDisplay("Erreur", StatusTone.ERROR), brokerConnectionDisplay("error"))
    }

    @Test
    fun `deploying and pending connections are a warning`() {
        assertEquals(StatusDisplay("Déploiement", StatusTone.WARNING), brokerConnectionDisplay("deploying"))
        assertEquals(StatusDisplay("En attente", StatusTone.WARNING), brokerConnectionDisplay("pending"))
    }

    @Test
    fun `null or unknown connection status stays neutral`() {
        assertEquals(StatusDisplay("Inconnu", StatusTone.NEUTRAL), brokerConnectionDisplay(null))
        assertEquals(StatusDisplay("Inconnu", StatusTone.NEUTRAL), brokerConnectionDisplay(""))
        assertEquals(StatusDisplay("Revoked", StatusTone.NEUTRAL), brokerConnectionDisplay("revoked"))
    }

    // ── brokerDisplayName ────────────────────────────────────────────────────

    @Test
    fun `broker code is turned into a readable name`() {
        assertEquals("Interactive brokers", brokerDisplayName("interactive_brokers"))
        assertEquals("Ibkr", brokerDisplayName("ibkr"))
    }

    // ── formatUptime ─────────────────────────────────────────────────────────

    @Test
    fun `formatUptime renders days hours and minutes`() {
        assertEquals("2j 4h 30m", formatUptime(2 * 86_400L + 4 * 3_600L + 30 * 60L))
        assertEquals("3h", formatUptime(3 * 3_600L))
        assertEquals("45m 12s", formatUptime(45 * 60L + 12L))
        assertEquals("9s", formatUptime(9L))
        assertEquals("—", formatUptime(-1L))
    }
}
