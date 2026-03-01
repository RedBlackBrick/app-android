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
 */
@Singleton
class VpnRequiredInterceptor @Inject constructor(
    private val vpnManager: WireGuardManager,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        // En debug : bypass VPN pour permettre les tests sur backend local (make dev).
        // Le BuildConfig.DEBUG est false en release — aucun impact production.
        if (BuildConfig.DEBUG) return chain.proceed(chain.request())
        if (vpnManager.state.value !is VpnState.Connected) {
            throw VpnNotConnectedException()
        }
        return chain.proceed(chain.request())
    }
}
