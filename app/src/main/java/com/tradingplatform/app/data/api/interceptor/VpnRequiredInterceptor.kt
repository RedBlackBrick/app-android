package com.tradingplatform.app.data.api.interceptor

import com.tradingplatform.app.BuildConfig
import com.tradingplatform.app.vpn.VpnNotConnectedException
import com.tradingplatform.app.vpn.VpnState
import com.tradingplatform.app.vpn.WireGuardManager
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bloque toutes les requêtes si le tunnel VPN n'est pas actif.
 * Lève VpnNotConnectedException (extends Exception, pas IOException) —
 * ce qui permet un catch distinct dans WidgetUpdateWorker sans déclencher les retries WorkManager.
 *
 * En DEV_MODE : bypass complet du VPN pour permettre les tests sur backend local sans WireGuard.
 * DEV_MODE est distinct de DEBUG — en debug sans DEV_MODE, le VPN reste requis.
 * DEV_MODE est toujours false en release (BuildConfig.DEV_MODE hardcodé à false dans buildTypes.release).
 */
@Singleton
class VpnRequiredInterceptor @Inject constructor(
    private val vpnManager: WireGuardManager,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        if (BuildConfig.DEV_MODE) return chain.proceed(chain.request())
        if (vpnManager.state.value !is VpnState.Connected) {
            throw VpnNotConnectedException()
        }
        return chain.proceed(chain.request())
    }
}
