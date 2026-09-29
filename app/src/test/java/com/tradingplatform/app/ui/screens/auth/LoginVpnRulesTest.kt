package com.tradingplatform.app.ui.screens.auth

import com.tradingplatform.app.ui.screens.settings.VpnConsentUiState
import com.tradingplatform.app.vpn.VpnNotConnectedException
import com.tradingplatform.app.vpn.VpnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class LoginVpnRulesTest {

    // ── loginVpnBanner ────────────────────────────────────────────────────────

    @Test
    fun `Connected and SystemVpnActive show the discreet active pill without actions`() {
        listOf(VpnState.Connected(), VpnState.SystemVpnActive).forEach { state ->
            val banner = loginVpnBanner(state, hasVpnConfig = true, consentDenied = false)

            assertEquals(LoginVpnBannerKind.ACTIVE, banner.kind)
            assertEquals("VPN actif", banner.title)
            assertNull(banner.detail)
            assertFalse(banner.showActivate)
        }
    }

    @Test
    fun `Connecting shows the progress pill without actions`() {
        val banner = loginVpnBanner(VpnState.Connecting, hasVpnConfig = true, consentDenied = false)

        assertEquals(LoginVpnBannerKind.CONNECTING, banner.kind)
        assertEquals("Connexion au VPN…", banner.title)
        assertFalse(banner.showActivate)
    }

    @Test
    fun `Disconnected with a config asks to activate the VPN`() {
        val banner = loginVpnBanner(VpnState.Disconnected, hasVpnConfig = true, consentDenied = false)

        assertEquals(LoginVpnBannerKind.REQUIRED, banner.kind)
        assertEquals("VPN requis", banner.title)
        assertEquals("La connexion à la plateforme n'est possible que via le VPN.", banner.detail)
        assertTrue(banner.showActivate)
        assertEquals("Activer le VPN", banner.activateLabel)
    }

    @Test
    fun `Error behaves like Disconnected`() {
        val banner = loginVpnBanner(VpnState.Error("boom"), hasVpnConfig = true, consentDenied = false)

        assertEquals(LoginVpnBannerKind.REQUIRED, banner.kind)
        assertTrue(banner.showActivate)
    }

    @Test
    fun `Disconnected without a config hides the activate button and says no tunnel is configured`() {
        val banner = loginVpnBanner(VpnState.Disconnected, hasVpnConfig = false, consentDenied = false)

        assertEquals(LoginVpnBannerKind.REQUIRED, banner.kind)
        assertFalse(banner.showActivate)
        assertEquals(
            "Aucun tunnel n'est configuré dans l'application : activez votre VPN externe.",
            banner.detail,
        )
    }

    @Test
    fun `ConsentRequired keeps the activate button even before the config is read`() {
        val banner = loginVpnBanner(VpnState.ConsentRequired, hasVpnConfig = false, consentDenied = false)

        assertEquals(LoginVpnBannerKind.REQUIRED, banner.kind)
        assertTrue(banner.showActivate)
        assertEquals("Activer le VPN", banner.activateLabel)
        assertEquals("Android doit d'abord autoriser l'application à créer le tunnel VPN.", banner.detail)
        assertFalse(banner.consentDenied)
    }

    @Test
    fun `denied consent shows the explicit refusal and a retry label`() {
        val banner = loginVpnBanner(VpnState.ConsentRequired, hasVpnConfig = true, consentDenied = true)

        assertTrue(banner.consentDenied)
        assertEquals("Réessayer", banner.activateLabel)
        assertEquals(
            "Autorisation VPN refusée — le tunnel est requis pour utiliser l'application",
            banner.detail,
        )
    }

    // ── nextVpnConsentState ───────────────────────────────────────────────────

    @Test
    fun `ConsentRequired after Activate requests the system dialog`() {
        val next = nextVpnConsentState(VpnConsentUiState.None, VpnState.ConsentRequired, activationRequested = true)

        assertEquals(VpnConsentUiState.Required, next)
    }

    @Test
    fun `ConsentRequired without a user action never pops the system dialog`() {
        val next = nextVpnConsentState(VpnConsentUiState.None, VpnState.ConsentRequired, activationRequested = false)

        assertEquals(VpnConsentUiState.None, next)
    }

    @Test
    fun `Launched and Denied are kept while ConsentRequired persists`() {
        assertEquals(
            VpnConsentUiState.Launched,
            nextVpnConsentState(VpnConsentUiState.Launched, VpnState.ConsentRequired, activationRequested = true),
        )
        assertEquals(
            VpnConsentUiState.Denied,
            nextVpnConsentState(VpnConsentUiState.Denied, VpnState.ConsentRequired, activationRequested = true),
        )
    }

    @Test
    fun `leaving ConsentRequired resets Denied and Required but not a pending Launched`() {
        assertEquals(
            VpnConsentUiState.None,
            nextVpnConsentState(VpnConsentUiState.Denied, VpnState.Disconnected, activationRequested = false),
        )
        assertEquals(
            VpnConsentUiState.None,
            nextVpnConsentState(VpnConsentUiState.Required, VpnState.Connecting, activationRequested = true),
        )
        assertEquals(
            VpnConsentUiState.Launched,
            nextVpnConsentState(VpnConsentUiState.Launched, VpnState.Connecting, activationRequested = true),
        )
    }

    // ── loginNetworkErrorMessage / loginTimeoutMessage ────────────────────────

    @Test
    fun `timeout connect refusal unknown host and no route without a tunnel say VPN required`() {
        val errors = listOf(
            SocketTimeoutException("timeout"),
            ConnectException("Failed to connect to /10.42.0.1:443"),
            UnknownHostException("vps"),
            NoRouteToHostException("No route to host"),
        )

        errors.forEach { error ->
            assertEquals(
                "VPN requis — activez le tunnel puis réessayez",
                loginNetworkErrorMessage(error, VpnState.Disconnected),
            )
        }
    }

    @Test
    fun `every non-usable state maps a timeout to VPN required`() {
        listOf(VpnState.Disconnected, VpnState.Connecting, VpnState.ConsentRequired, VpnState.Error("x"))
            .forEach { state ->
                assertEquals(
                    "VPN requis — activez le tunnel puis réessayez",
                    loginNetworkErrorMessage(SocketTimeoutException(), state),
                )
            }
    }

    @Test
    fun `VpnNotConnectedException always says VPN required`() {
        assertEquals(
            "VPN requis — activez le tunnel puis réessayez",
            loginNetworkErrorMessage(VpnNotConnectedException(), VpnState.Connected()),
        )
    }

    @Test
    fun `a network error with a usable tunnel says the server is unreachable`() {
        listOf(VpnState.Connected(), VpnState.SystemVpnActive).forEach { state ->
            assertEquals(
                "Serveur injoignable — réessayez dans un instant",
                loginNetworkErrorMessage(SocketTimeoutException(), state),
            )
        }
    }

    @Test
    fun `a wrapped network error is still recognised through its cause chain`() {
        val wrapped = RuntimeException("Login failed", SocketTimeoutException("timeout"))

        assertEquals(
            "VPN requis — activez le tunnel puis réessayez",
            loginNetworkErrorMessage(wrapped, VpnState.Disconnected),
        )
    }

    @Test
    fun `non network errors keep their own message`() {
        assertNull(loginNetworkErrorMessage(IllegalStateException("Login failed: HTTP 500"), VpnState.Disconnected))
        assertNull(loginNetworkErrorMessage(IOException("closed"), VpnState.Disconnected))
    }

    @Test
    fun `global login timeout blames the VPN only when the tunnel is not usable`() {
        assertEquals("VPN requis — activez le tunnel puis réessayez", loginTimeoutMessage(VpnState.Disconnected))
        assertEquals("La connexion a expiré — réessayez", loginTimeoutMessage(VpnState.Connected()))
        assertEquals("La connexion a expiré — réessayez", loginTimeoutMessage(VpnState.SystemVpnActive))
    }

    @Test
    fun `only Connected and SystemVpnActive are usable tunnels`() {
        assertTrue(isVpnUsable(VpnState.Connected()))
        assertTrue(isVpnUsable(VpnState.SystemVpnActive))
        assertFalse(isVpnUsable(VpnState.Connecting))
        assertFalse(isVpnUsable(VpnState.Disconnected))
        assertFalse(isVpnUsable(VpnState.ConsentRequired))
        assertFalse(isVpnUsable(VpnState.Error("x")))
    }
}
