package com.tradingplatform.app.ui.components

import com.tradingplatform.app.vpn.VpnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Logique pure des schémas de connexion : libellé, teinte, liaison, animé ou non. */
class ConnectionDiagramModelTest {

    @Test
    fun `Connected shows an active flowing tunnel with the encrypted label`() {
        val spec = vpnTunnelSpec(VpnState.Connected())

        assertEquals(DiagramTone.ACTIVE, spec.tone)
        assertEquals(LinkStyle.FLOWING, spec.link)
        assertTrue(spec.animated)
        assertEquals("Tunnel chiffré actif", spec.label)
    }

    @Test
    fun `Connected with a server ip gives the same spec`() {
        assertEquals(vpnTunnelSpec(VpnState.Connected()), vpnTunnelSpec(VpnState.Connected("10.42.0.1")))
    }

    @Test
    fun `SystemVpnActive is active and flowing but labelled as an external VPN`() {
        val spec = vpnTunnelSpec(VpnState.SystemVpnActive)

        assertEquals(DiagramTone.ACTIVE, spec.tone)
        assertEquals(LinkStyle.FLOWING, spec.link)
        assertTrue(spec.animated)
        assertEquals("VPN externe actif", spec.label)
    }

    @Test
    fun `Connecting shows a pulsing dashed pending tunnel`() {
        val spec = vpnTunnelSpec(VpnState.Connecting)

        assertEquals(DiagramTone.PENDING, spec.tone)
        assertEquals(LinkStyle.DASHED, spec.link)
        assertTrue(spec.animated)
        assertEquals("Connexion…", spec.label)
    }

    @Test
    fun `Disconnected Error and ConsentRequired show a static cut line`() {
        val states = listOf(VpnState.Disconnected, VpnState.ConsentRequired, VpnState.Error("boom"))

        states.forEach { state ->
            val spec = vpnTunnelSpec(state)
            assertEquals(DiagramTone.INACTIVE, spec.tone)
            assertEquals(LinkStyle.CUT, spec.link)
            assertFalse(spec.animated)
            assertEquals("Non connecté", spec.label)
        }
    }

    @Test
    fun `descriptions differ per state so TalkBack tells them apart`() {
        val descriptions = listOf(
            VpnState.Connected(),
            VpnState.SystemVpnActive,
            VpnState.Connecting,
            VpnState.Disconnected,
        ).map { vpnTunnelSpec(it).description }

        assertEquals(4, descriptions.toSet().size)
        assertEquals(
            "Schéma du tunnel VPN : téléphone non connecté au serveur, liaison coupée",
            vpnTunnelSpec(VpnState.Disconnected).description,
        )
    }

    @Test
    fun `online device shows an active flowing link`() {
        val spec = deviceLinkSpec(online = true)

        assertEquals(DiagramTone.ACTIVE, spec.tone)
        assertEquals(LinkStyle.FLOWING, spec.link)
        assertTrue(spec.animated)
        assertEquals("Liaison WireGuard active", spec.label)
        assertEquals("Liaison de l'appareil Radxa au serveur par WireGuard : en ligne", spec.description)
    }

    @Test
    fun `offline device shows a static cut link`() {
        val spec = deviceLinkSpec(online = false)

        assertEquals(DiagramTone.INACTIVE, spec.tone)
        assertEquals(LinkStyle.CUT, spec.link)
        assertFalse(spec.animated)
        assertEquals("Hors ligne — liaison interrompue", spec.label)
    }

    @Test
    fun `motion is allowed only with a non-zero animator scale outside previews`() {
        assertTrue(isMotionAllowed(animatorDurationScale = 1f, inspectionMode = false))
        assertTrue(isMotionAllowed(animatorDurationScale = 0.5f, inspectionMode = false))
        assertFalse(isMotionAllowed(animatorDurationScale = 0f, inspectionMode = false))
        assertFalse(isMotionAllowed(animatorDurationScale = 1f, inspectionMode = true))
    }
}
