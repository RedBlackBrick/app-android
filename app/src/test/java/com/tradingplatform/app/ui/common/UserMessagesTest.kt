package com.tradingplatform.app.ui.common

import com.tradingplatform.app.domain.exception.HttpStatusException
import com.tradingplatform.app.domain.exception.InvalidCredentialsException
import com.tradingplatform.app.vpn.VpnNotConnectedException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class UserMessagesTest {

    @Test
    fun `repository style HTTP 500 error is translated, never shown raw`() {
        val message = IllegalStateException("Get my peers failed: HTTP 500").toUserMessage()
        assertEquals("Le serveur rencontre un problème — réessayez dans un instant", message)
        assertFalse(message.contains("HTTP"))
    }

    @Test
    fun `status codes map to French messages`() {
        assertEquals("Session expirée — reconnectez-vous", HttpStatusException(401, "/x").toUserMessage())
        assertEquals("Accès refusé", IllegalStateException("failed: HTTP 403").toUserMessage())
        assertEquals("Donnée introuvable", IllegalStateException("failed: HTTP 404").toUserMessage())
        assertEquals(
            "Trop de requêtes — patientez quelques secondes",
            HttpStatusException(429, "/x").toUserMessage(),
        )
    }

    @Test
    fun `vpn and network errors are explained`() {
        assertEquals(
            "VPN non connecté — activez le tunnel pour continuer",
            VpnNotConnectedException().toUserMessage(),
        )
        assertEquals(
            "Le serveur met trop de temps à répondre — réessayez",
            SocketTimeoutException("timeout").toUserMessage(),
        )
        assertEquals(
            "Serveur injoignable — vérifiez le VPN et votre connexion",
            IOException("EHOSTUNREACH").toUserMessage(),
        )
    }

    @Test
    fun `domain exceptions keep their own French message`() {
        assertEquals(
            "Email ou mot de passe incorrect",
            InvalidCredentialsException().toUserMessage(),
        )
    }

    @Test
    fun `unknown technical errors fall back to the supplied message`() {
        assertEquals("Erreur de chargement", RuntimeException("NPE at Foo.kt:12").toUserMessage("Erreur de chargement"))
        assertEquals(GENERIC_ERROR, RuntimeException("boom").toUserMessage())
    }
}
