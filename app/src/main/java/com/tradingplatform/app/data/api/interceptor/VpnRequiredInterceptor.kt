package com.tradingplatform.app.data.api.interceptor

import com.tradingplatform.app.BuildConfig
import com.tradingplatform.app.vpn.SystemVpnMonitor
import com.tradingplatform.app.vpn.VpnNotConnectedException
import com.tradingplatform.app.vpn.VpnState
import com.tradingplatform.app.vpn.WireGuardManager
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bloque toutes les requêtes si aucun tunnel VPN n'est actif.
 *
 * Deux tunnels possibles :
 *   1. Le WireGuard intégré de l'app (via [WireGuardManager]).
 *   2. Un VPN système-level monté par une AUTRE app (WireGuard officielle, OpenVPN,
 *      Cloudflare WARP, etc.) — détecté via [SystemVpnMonitor].
 *
 * Le cas (2) est critique : sans cette détection, les utilisateurs qui activent leur
 * tunnel depuis l'app WireGuard officielle voient leurs requêtes refusées alors que
 * le tunnel EST bien monté au niveau système.
 *
 * En DEV_MODE : bypass complet du VPN pour permettre les tests sur backend local sans WireGuard.
 * DEV_MODE est distinct de DEBUG — en debug sans DEV_MODE, le VPN reste requis.
 * DEV_MODE est toujours false en release (BuildConfig.DEV_MODE hardcodé à false dans buildTypes.release).
 */
@Singleton
class VpnRequiredInterceptor @Inject constructor(
    private val vpnManager: WireGuardManager,
    private val systemVpnMonitor: SystemVpnMonitor,
) : Interceptor {

    companion object {
        private val VPN_EXCLUDED_PATHS = setOf(
            "/v1/auth/login",
            "/v1/auth/refresh",
            "/v1/auth/2fa/verify",
            "/v1/auth/csrf-token",
            "/csrf-token",
        )
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        if (BuildConfig.DEV_MODE) return chain.proceed(chain.request())
        if (chain.request().url.encodedPath in VPN_EXCLUDED_PATHS) {
            return chain.proceed(chain.request())
        }
        if (vpnManager.state.value is VpnState.Connected) {
            return chain.proceed(chain.request())
        }
        if (systemVpnMonitor.active.value) {
            return chain.proceed(chain.request())
        }
        throw VpnNotConnectedException()
    }
}
