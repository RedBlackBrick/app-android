package com.tradingplatform.app.security

import timber.log.Timber
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * TrustManager permissif pour le port 8099 du Radxa en RFC-1918.
 *
 * Le pairing-server expose un certificat auto-signé régénérable ; l'authenticité
 * du device est portée par la couche applicative (libsodium `crypto_box_seal`
 * avec la clé publique WireGuard issue du QR code scanné par l'utilisateur) et
 * par le garde [isLocalNetwork] au niveau Repository.
 *
 * Défense en profondeur côté TLS :
 *  - TLS ≥ 1.2 (configuré dans le SSLContext par défaut du system provider)
 *  - TrustManager n'échoue jamais sur un cert serveur (les appels LAN passent
 *    toujours par un Repository qui a préalablement validé l'IP RFC-1918)
 *  - Les clients non-LAN (VPS) continuent d'utiliser le pinning Root CA Caddy
 *
 * Évolution future (TOFU — *non implémenté ici*) : capturer le SHA-256 SPKI au
 * pairing et l'enforcer ensuite via un `CertificatePinner` keyed sur deviceId.
 * La persistance du cert côté device (`/var/data/pairing/server.pem`) est
 * déjà en place pour préparer ce pas.
 */
object LanTrustManager {

    private val permissiveTrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
            // Pas d'auth client LAN — no-op
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            if (chain.isNotEmpty()) {
                val leaf = chain[0]
                Timber.v(
                    "[LAN-TLS] Accepting self-signed cert subject=%s serial=%s",
                    leaf.subjectX500Principal.name,
                    leaf.serialNumber.toString(16),
                )
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * Retourne une paire `(SSLSocketFactory, X509TrustManager)` à injecter dans
     * `OkHttpClient.Builder.sslSocketFactory(...)`.
     */
    fun socketFactory(): Pair<SSLSocketFactory, X509TrustManager> {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(permissiveTrustManager), java.security.SecureRandom())
        return ctx.socketFactory to permissiveTrustManager
    }
}
