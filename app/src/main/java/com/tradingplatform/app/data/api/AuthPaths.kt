package com.tradingplatform.app.data.api

/**
 * Source unique des chemins d'authentification utilisés par la chaîne OkHttp
 * ([interceptor.AuthInterceptor], [interceptor.CsrfInterceptor],
 * [interceptor.VpnRequiredInterceptor], [interceptor.EncryptedCookieJar],
 * [interceptor.TokenAuthenticator]).
 *
 * Toute comparaison se fait sur `HttpUrl.encodedPath` en égalité exacte — jamais de
 * `contains()` / `startsWith()` qui matcherait un futur endpoint par accident.
 *
 * Miroir du backend (trading-platform2) — à maintenir en phase :
 * - `app/core/middleware/auth.py` → `PUBLIC_PATHS` (endpoints sans JWT)
 * - `app/core/middleware/csrf.py` → `CSRF_EXEMPT_PATHS`
 * - `app/auth/router.py` → `/verify-2fa` + alias `/2fa/verify` (même handler), qui pose
 *   le cookie httpOnly `refresh_token` après une vérification TOTP réussie.
 *
 * `/v1/auth/register` n'y figure pas : endpoint admin côté backend, jamais appelé par l'app.
 */
object AuthPaths {
    const val LOGIN = "/v1/auth/login"
    const val REFRESH = "/v1/auth/refresh"
    const val TOTP_VERIFY = "/v1/auth/2fa/verify"
    const val TOTP_VERIFY_ALIAS = "/v1/auth/verify-2fa"
    const val CSRF = "/v1/auth/csrf-token"
    const val CSRF_LEGACY = "/csrf-token"

    private const val FCM_TOKEN = "/v1/notifications/fcm-token"

    /**
     * Endpoints qui n'exigent pas de Bearer. [interceptor.AuthInterceptor] les laisse passer
     * même si le TokenHolder est vide (install fraîche, login 2FA en cours : le temp token
     * du body est la seule credential de `/2fa/verify`).
     */
    val PUBLIC: Set<String> = setOf(LOGIN, REFRESH, TOTP_VERIFY, TOTP_VERIFY_ALIAS, CSRF, CSRF_LEGACY)

    /**
     * Endpoints exemptés du token CSRF côté backend. `/2fa/verify` n'est volontairement
     * PAS exempt : le token CSRF anonyme (obtenu sans Bearer) y est accepté.
     */
    val CSRF_EXEMPT: Set<String> = setOf(LOGIN, REFRESH, CSRF)

    /**
     * Endpoints autorisés sans tunnel VPN actif (décision produit : l'auth doit rester
     * joignable pour afficher des erreurs explicites). Égal à [PUBLIC].
     */
    val VPN_EXCLUDED: Set<String> = PUBLIC

    /** Réponses dont le cookie `refresh_token` (Set-Cookie httpOnly) doit être persisté. */
    val COOKIE_SAVE: Set<String> = setOf(LOGIN, REFRESH, TOTP_VERIFY, TOTP_VERIFY_ALIAS)

    /**
     * true si les requêtes/réponses de ce chemin transportent des secrets (mot de passe,
     * tokens, code TOTP, token FCM) — leur body ne doit jamais être loggé.
     */
    fun isSensitive(path: String): Boolean =
        path.startsWith("/v1/auth/") || path == CSRF_LEGACY || path == FCM_TOKEN
}
