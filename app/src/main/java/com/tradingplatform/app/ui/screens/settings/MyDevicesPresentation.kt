package com.tradingplatform.app.ui.screens.settings

import com.tradingplatform.app.domain.model.VpnPeer
import com.tradingplatform.app.domain.model.VpnPeerType
import java.time.Duration
import java.time.Instant

/** Handshake WireGuard « récent » : le keepalive tient le tunnel sous cette limite. */
private const val RECENT_HANDSHAKE_MINUTES = 3L

enum class PeerStatus(val label: String) {
    ONLINE("En ligne"),
    OFFLINE("Hors ligne"),
    REVOKED("Révoqué"),
}

/**
 * Statut affiché d'un appareil. `vpn_peers.last_handshake` n'est jamais renseigné côté serveur :
 * quand le backend fournit [VpnPeer.isOnline] (heartbeats de la carte), c'est lui qui fait foi ;
 * sinon repli sur le handshake WireGuard.
 */
fun VpnPeer.status(now: Instant = Instant.now()): PeerStatus = when {
    !isActive -> PeerStatus.REVOKED
    isOnline ?: hasRecentHandshake(now) -> PeerStatus.ONLINE
    else -> PeerStatus.OFFLINE
}

private fun VpnPeer.hasRecentHandshake(now: Instant): Boolean =
    lastHandshake?.let { Duration.between(it, now).toMinutes() < RECENT_HANDSHAKE_MINUTES } == true

fun VpnPeerType.roleLabel(): String = when (this) {
    VpnPeerType.RADXA_BOARD -> "Carte Radxa"
    VpnPeerType.ANDROID_APP -> "Téléphone"
    VpnPeerType.WEB_CLIENT -> "Ordinateur"
}

/** « Vu à l'instant », « Vu il y a 5 min », « Jamais vu »… */
fun formatLastSeen(lastActivity: Instant?, now: Instant = Instant.now()): String {
    if (lastActivity == null) return "Jamais vu"
    val diff = Duration.between(lastActivity, now)
    return when {
        diff.isNegative || diff.toMinutes() < 1 -> "Vu à l'instant"
        diff.toMinutes() < 60 -> "Vu il y a ${diff.toMinutes()} min"
        diff.toHours() < 24 -> "Vu il y a ${diff.toHours()} h"
        else -> "Vu il y a ${diff.toDays()} j"
    }
}

/** Actifs d'abord (en ligne en tête, puis les plus récemment appairés), révoqués à part. */
data class PeerGroups(val active: List<VpnPeer>, val revoked: List<VpnPeer>)

fun groupPeers(peers: List<VpnPeer>, now: Instant = Instant.now()): PeerGroups {
    val (active, revoked) = peers.partition { it.isActive }
    return PeerGroups(
        active = active.sortedWith(
            compareByDescending<VpnPeer> { it.status(now) == PeerStatus.ONLINE }
                .thenByDescending { it.pairedAt },
        ),
        revoked = revoked.sortedByDescending { it.pairedAt },
    )
}
