package com.tradingplatform.app.data.api.interceptor

import com.tradingplatform.app.data.session.SessionManager
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Détecte HTTP 426 Upgrade Required (VPS rejette la version de l'app trop ancienne)
 * et notifie [SessionManager] pour que l'UI affiche un dialog bloquant.
 *
 * Placé en PREMIÈRE position de la chaîne OkHttp (avant CSRF) pour intercepter
 * toute réponse 426 quelle que soit la requête déclencheuse.
 *
 * L'interceptor laisse passer la réponse 426 sans la consommer — le corps n'est pas
 * lu ici pour ne pas perturber les éventuels callers Retrofit en aval.
 *
 * Voir CLAUDE.md §9 — Compatibilité API.
 */
@Singleton
class UpgradeRequiredInterceptor @Inject constructor(
    private val sessionManager: SessionManager,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code == 426) {
            Timber.w("UpgradeRequiredInterceptor: HTTP 426 — app version rejected by server")
            sessionManager.notifyUpgradeRequired()
        }
        return response
    }
}
