package com.tradingplatform.app.vpn

import android.content.Context
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel

/**
 * Minimal seam over the wireguard-android [Backend] used by [WireGuardManager].
 *
 * Exists so the manager's concurrency logic (Mutex, superseded requests, DOWN callback)
 * can be unit-tested on the JVM with a fake — [GoBackend] needs a real Android runtime.
 *
 * Contract (mirrors [GoBackend]): [setState] is blocking/long-running, invokes
 * [Tunnel.onStateChange] synchronously for every transition it performs, and returns the
 * resulting state of [tunnel].
 */
interface TunnelBackend {
    /** Brings [tunnel] to [state]. [config] is required for [Tunnel.State.UP], ignored otherwise. */
    suspend fun setState(tunnel: Tunnel, state: Tunnel.State, config: WireGuardConfig?): Tunnel.State

    suspend fun getState(tunnel: Tunnel): Tunnel.State
}

/**
 * Production [TunnelBackend] backed by [GoBackend] (userspace wireguard-go).
 *
 * The tunnel itself is held by `com.wireguard.android.backend.GoBackend$VpnService`, declared
 * by the wireguard `tunnel` AAR manifest (BIND_VPN_SERVICE + `android.net.VpnService` filter).
 *
 * Callers must invoke these methods off the main thread (WireGuardManager uses its IO dispatcher):
 * the underlying [GoBackend] calls block.
 */
internal class GoTunnelBackend(context: Context) : TunnelBackend {

    private val backend: Backend = GoBackend(context.applicationContext)

    override suspend fun setState(
        tunnel: Tunnel,
        state: Tunnel.State,
        config: WireGuardConfig?,
    ): Tunnel.State = backend.setState(tunnel, state, config?.let(::toLibConfig))

    override suspend fun getState(tunnel: Tunnel): Tunnel.State = backend.getState(tunnel)

    /**
     * Converts our [WireGuardConfig] domain model into a [com.wireguard.config.Config].
     *
     * IMPORTANT: [WireGuardConfig.privateKey] is never logged here (CLAUDE.md §1).
     */
    private fun toLibConfig(config: WireGuardConfig): com.wireguard.config.Config {
        val interfaceBuilder = com.wireguard.config.Interface.Builder()
            .parsePrivateKey(config.privateKey)   // private key — never log
            .parseAddresses(config.address)
            .parseDnsServers(config.dns)

        val peerBuilder = com.wireguard.config.Peer.Builder()
            .parsePublicKey(config.peer.publicKey)
            .parseAllowedIPs(config.peer.allowedIPs)
            .parseEndpoint(config.peer.endpoint)
            .apply {
                if (config.peer.persistentKeepalive > 0) {
                    parsePersistentKeepalive(config.peer.persistentKeepalive.toString())
                }
                config.peer.presharedKey?.let { parsePreSharedKey(it) }
            }

        return com.wireguard.config.Config.Builder()
            .setInterface(interfaceBuilder.build())
            .addPeer(peerBuilder.build())
            .build()
    }
}
