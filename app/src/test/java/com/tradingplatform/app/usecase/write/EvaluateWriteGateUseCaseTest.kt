package com.tradingplatform.app.usecase.write

import com.tradingplatform.app.data.session.TokenHolder
import com.tradingplatform.app.domain.usecase.write.EvaluateWriteGateUseCase
import com.tradingplatform.app.domain.usecase.write.WriteBlockReason
import com.tradingplatform.app.domain.usecase.write.WriteGate
import com.tradingplatform.app.vpn.SystemVpnMonitor
import com.tradingplatform.app.vpn.VpnState
import com.tradingplatform.app.vpn.WireGuardManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Garde d'écriture — session, VPN et fraîcheur de la donnée visée.
 *
 * **Priorité documentée** quand plusieurs conditions échouent (une seule raison est renvoyée) :
 * `NOT_LOGGED_IN` > `VPN_NOT_CONNECTED` > `DATA_STALE`.
 * - sans session, rien d'autre n'a de sens ;
 * - sans VPN, le conseil « actualisez les données » serait irréalisable (le rafraîchissement
 *   exige le tunnel), donc le VPN passe avant la fraîcheur.
 */
class EvaluateWriteGateUseCaseTest {

    private val now = 1_800_000_000_000L
    private val inAppState = MutableStateFlow<VpnState>(VpnState.Disconnected)
    private val systemVpnActive = MutableStateFlow(false)
    private var systemVpnLiveRead = false

    private val wireGuardManager = mockk<WireGuardManager> {
        every { state } returns inAppState
    }
    private val systemVpnMonitor = mockk<SystemVpnMonitor> {
        every { active } returns systemVpnActive
        every { isActiveNow() } answers { systemVpnLiveRead }
    }
    private val tokenHolder = TokenHolder().apply { setToken("jwt-access-token") }

    private fun useCase(vpnRequired: Boolean = true) = EvaluateWriteGateUseCase(
        wireGuardManager = wireGuardManager,
        systemVpnMonitor = systemVpnMonitor,
        tokenHolder = tokenHolder,
        clock = { now },
        vpnRequired = vpnRequired,
    )

    private fun blocked(reason: WriteBlockReason, message: String) = WriteGate.Blocked(reason, message)

    // ── VPN ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `in-app tunnel connected with fresh data is allowed`() {
        inAppState.value = VpnState.Connected(serverIp = "10.42.0.1")

        assertEquals(WriteGate.Allowed, useCase()(dataSyncedAt = now - 1_000L))
    }

    @Test
    fun `third-party system VPN reported by the monitor is allowed while the in-app tunnel is down`() {
        inAppState.value = VpnState.Disconnected
        systemVpnActive.value = true

        assertEquals(WriteGate.Allowed, useCase()(dataSyncedAt = now - 1_000L))
    }

    @Test
    fun `system VPN found only by the live re-read is allowed`() {
        inAppState.value = VpnState.Disconnected
        systemVpnActive.value = false
        systemVpnLiveRead = true

        assertEquals(WriteGate.Allowed, useCase()(dataSyncedAt = now - 1_000L))
    }

    @Test
    fun `no VPN at all is blocked with VPN_NOT_CONNECTED`() {
        assertEquals(
            blocked(WriteBlockReason.VPN_NOT_CONNECTED, "VPN requis — activez le tunnel puis réessayez."),
            useCase()(dataSyncedAt = now - 1_000L),
        )
    }

    @Test
    fun `connecting, consent required and error states do not count as a VPN`() {
        val expected = blocked(
            WriteBlockReason.VPN_NOT_CONNECTED,
            "VPN requis — activez le tunnel puis réessayez.",
        )
        listOf(VpnState.Connecting, VpnState.ConsentRequired, VpnState.Error("boom")).forEach { state ->
            inAppState.value = state
            assertEquals("state $state", expected, useCase()(dataSyncedAt = now - 1_000L))
        }
    }

    @Test
    fun `dev mode does not require a VPN but still checks session and freshness`() {
        val devGate = useCase(vpnRequired = false)

        assertEquals(WriteGate.Allowed, devGate(dataSyncedAt = now - 1_000L))
        assertEquals(
            blocked(WriteBlockReason.DATA_STALE, "Données trop anciennes — actualisez l'écran avant d'agir."),
            devGate(dataSyncedAt = null),
        )
    }

    // ── Fraîcheur ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `data older than the default 60 s is stale`() {
        inAppState.value = VpnState.Connected()

        assertEquals(
            blocked(WriteBlockReason.DATA_STALE, "Données trop anciennes — actualisez l'écran avant d'agir."),
            useCase()(dataSyncedAt = now - 60_001L),
        )
    }

    @Test
    fun `data exactly at the maximum age is still fresh`() {
        inAppState.value = VpnState.Connected()

        assertEquals(WriteGate.Allowed, useCase()(dataSyncedAt = now - 60_000L))
        assertEquals(WriteGate.Allowed, useCase()(dataSyncedAt = now))
    }

    @Test
    fun `unknown sync time is stale`() {
        inAppState.value = VpnState.Connected()

        assertEquals(
            blocked(WriteBlockReason.DATA_STALE, "Données trop anciennes — actualisez l'écran avant d'agir."),
            useCase()(dataSyncedAt = null),
        )
    }

    @Test
    fun `sync time in the future (clock moved back) is stale`() {
        inAppState.value = VpnState.Connected()

        assertEquals(
            blocked(WriteBlockReason.DATA_STALE, "Données trop anciennes — actualisez l'écran avant d'agir."),
            useCase()(dataSyncedAt = now + 1L),
        )
    }

    @Test
    fun `custom maximum age is honoured in both directions`() {
        inAppState.value = VpnState.Connected()
        val fiveSecondsOld = now - 5_000L

        assertEquals(WriteGate.Allowed, useCase()(dataSyncedAt = fiveSecondsOld, maxAgeMs = 10_000L))
        assertEquals(
            blocked(WriteBlockReason.DATA_STALE, "Données trop anciennes — actualisez l'écran avant d'agir."),
            useCase()(dataSyncedAt = fiveSecondsOld, maxAgeMs = 1_000L),
        )
    }

    // ── Session ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `missing access token is blocked with NOT_LOGGED_IN`() {
        inAppState.value = VpnState.Connected()
        tokenHolder.clear()

        assertEquals(
            blocked(WriteBlockReason.NOT_LOGGED_IN, "Session expirée — reconnectez-vous."),
            useCase()(dataSyncedAt = now - 1_000L),
        )
    }

    @Test
    fun `blank access token counts as no session`() {
        inAppState.value = VpnState.Connected()
        tokenHolder.setToken("   ")

        assertEquals(
            blocked(WriteBlockReason.NOT_LOGGED_IN, "Session expirée — reconnectez-vous."),
            useCase()(dataSyncedAt = now - 1_000L),
        )
    }

    // ── Priorité entre raisons : NOT_LOGGED_IN > VPN_NOT_CONNECTED > DATA_STALE ─────────────────

    @Test
    fun `priority - no session wins over no VPN and stale data`() {
        tokenHolder.clear()
        inAppState.value = VpnState.Disconnected

        val gate = useCase()(dataSyncedAt = null) as WriteGate.Blocked

        assertEquals(WriteBlockReason.NOT_LOGGED_IN, gate.reason)
    }

    @Test
    fun `priority - no VPN wins over stale data when a session exists`() {
        inAppState.value = VpnState.Disconnected

        val gate = useCase()(dataSyncedAt = now - 10 * 60_000L) as WriteGate.Blocked

        assertEquals(WriteBlockReason.VPN_NOT_CONNECTED, gate.reason)
    }

    @Test
    fun `priority - stale data is reported once session and VPN are fine`() {
        inAppState.value = VpnState.Connected()

        val gate = useCase()(dataSyncedAt = now - 10 * 60_000L) as WriteGate.Blocked

        assertEquals(WriteBlockReason.DATA_STALE, gate.reason)
    }
}
