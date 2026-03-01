package com.tradingplatform.app.security

import com.tradingplatform.app.BuildConfig
import okhttp3.CertificatePinner
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CertificatePinnerProvider @Inject constructor() {

    /**
     * Construit un CertificatePinner OkHttp avec le pin principal et le pin de backup.
     * Les deux pins sont obligatoires — un seul pin = app cassée si le cert VPS est renouvelé.
     * Les valeurs proviennent de BuildConfig (local.properties → BuildConfig).
     */
    fun buildCertificatePinner(hostname: String): CertificatePinner {
        // En debug : pas de pinning pour permettre les tests sur backend local HTTP.
        // BuildConfig.DEBUG est false en release — aucun impact production.
        if (BuildConfig.DEBUG) return CertificatePinner.Builder().build()
        return CertificatePinner.Builder()
            .add(hostname, BuildConfig.CERT_PIN_SHA256)
            .add(hostname, BuildConfig.CERT_PIN_SHA256_BACKUP)
            .build()
    }
}
