package com.tradingplatform.app.ui.screens.settings

import com.tradingplatform.app.domain.model.VpnPeer
import com.tradingplatform.app.domain.model.VpnPeerType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class MyDevicesPresentationTest {

    private val now = Instant.parse("2026-10-02T18:00:00Z")

    private fun peer(
        id: String,
        active: Boolean = true,
        isOnline: Boolean? = null,
        lastSeenAt: Instant? = null,
        lastHandshake: Instant? = null,
        pairedAt: Instant = Instant.parse("2026-04-01T00:00:00Z"),
    ) = VpnPeer(
        id = id,
        userId = 1,
        label = id,
        peerType = VpnPeerType.RADXA_BOARD,
        wgTunnelIp = "10.42.0.100",
        isActive = active,
        pairedAt = pairedAt,
        lastHandshake = lastHandshake,
        isOnline = isOnline,
        lastSeenAt = lastSeenAt,
    )

    @Test
    fun `server liveness wins over the never-written handshake`() {
        // Cas réel : radxa-05 envoie ses heartbeats mais vpn_peers.last_handshake est NULL.
        assertEquals(PeerStatus.ONLINE, peer("radxa-05", isOnline = true).status(now))
    }

    @Test
    fun `active peer whose board stopped heartbeating is offline`() {
        assertEquals(PeerStatus.OFFLINE, peer("radxa-01", isOnline = false).status(now))
    }

    @Test
    fun `revoked peer is revoked even if the board is online`() {
        assertEquals(PeerStatus.REVOKED, peer("radxa-02", active = false, isOnline = true).status(now))
    }

    @Test
    fun `falls back to the wireguard handshake when the server sends no liveness`() {
        assertEquals(
            PeerStatus.ONLINE,
            peer("a", lastHandshake = now.minusSeconds(60)).status(now),
        )
        assertEquals(
            PeerStatus.OFFLINE,
            peer("b", lastHandshake = now.minusSeconds(600)).status(now),
        )
        assertEquals(PeerStatus.OFFLINE, peer("c").status(now))
    }

    @Test
    fun `last activity is the most recent of heartbeat and handshake`() {
        val seen = Instant.parse("2026-10-02T17:55:00Z")
        val handshake = Instant.parse("2026-10-02T17:00:00Z")
        assertEquals(seen, peer("a", lastSeenAt = seen, lastHandshake = handshake).lastActivity)
    }

    @Test
    fun `last seen wording`() {
        assertEquals("Jamais vu", formatLastSeen(null, now))
        assertEquals("Vu à l'instant", formatLastSeen(now.minusSeconds(20), now))
        assertEquals("Vu il y a 5 min", formatLastSeen(now.minusSeconds(300), now))
        assertEquals("Vu il y a 3 h", formatLastSeen(now.minusSeconds(3 * 3600), now))
        assertEquals("Vu il y a 156 j", formatLastSeen(now.minusSeconds(156L * 86400), now))
    }

    @Test
    fun `groups put online boards first and revoked apart`() {
        val groups = groupPeers(
            listOf(
                peer("revoked", active = false),
                peer("offline", isOnline = false, pairedAt = Instant.parse("2026-09-01T00:00:00Z")),
                peer("online", isOnline = true, pairedAt = Instant.parse("2026-04-01T00:00:00Z")),
            ),
            now,
        )
        assertEquals(listOf("online", "offline"), groups.active.map { it.id })
        assertEquals(listOf("revoked"), groups.revoked.map { it.id })
    }
}
