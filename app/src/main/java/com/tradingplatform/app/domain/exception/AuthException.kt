package com.tradingplatform.app.domain.exception

/**
 * Credentials invalides (AUTH_1001).
 */
class InvalidCredentialsException(
    message: String = "Email ou mot de passe incorrect",
) : Exception(message)

/**
 * 2FA requis (AUTH_1004) — la session_token est incluse pour continuer vers TotpScreen.
 */
class TotpRequiredException(
    val sessionToken: String,
    message: String = "2FA requis",
) : Exception(message)

/**
 * Compte verrouillé (AUTH_1008 ou 429).
 * [retryAfterSeconds] est non-null si le serveur a fourni un délai Retry-After.
 */
class AccountLockedException(
    val retryAfterSeconds: Int?,
    message: String = "Compte verrouillé",
) : Exception(message)

/**
 * Code TOTP incorrect (2FA verify échoué).
 */
class InvalidTotpCodeException(
    message: String = "Code incorrect. Réessayez.",
) : Exception(message)

/**
 * État incohérent côté serveur — aucun portfolio trouvé après login.
 * Déclenche un logout forcé.
 */
class NoPortfolioException(
    message: String = "Aucun portfolio trouvé — état incohérent",
) : Exception(message)
