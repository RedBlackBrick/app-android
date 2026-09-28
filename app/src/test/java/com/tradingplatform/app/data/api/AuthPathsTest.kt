package com.tradingplatform.app.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verrouille le contenu exact de [AuthPaths] — miroir des sets backend
 * (auth.py PUBLIC_PATHS, csrf.py CSRF_EXEMPT_PATHS, auth/router.py cookies 2FA).
 * Toute modification doit être un choix délibéré, pas un effet de bord.
 */
class AuthPathsTest {

    @Test
    fun `constants match backend routes`() {
        assertEquals("/v1/auth/login", AuthPaths.LOGIN)
        assertEquals("/v1/auth/refresh", AuthPaths.REFRESH)
        assertEquals("/v1/auth/2fa/verify", AuthPaths.TOTP_VERIFY)
        assertEquals("/v1/auth/verify-2fa", AuthPaths.TOTP_VERIFY_ALIAS)
        assertEquals("/v1/auth/csrf-token", AuthPaths.CSRF)
        assertEquals("/csrf-token", AuthPaths.CSRF_LEGACY)
    }

    @Test
    fun `PUBLIC has exact contents`() {
        assertEquals(
            setOf(
                "/v1/auth/login",
                "/v1/auth/refresh",
                "/v1/auth/2fa/verify",
                "/v1/auth/verify-2fa",
                "/v1/auth/csrf-token",
                "/csrf-token",
            ),
            AuthPaths.PUBLIC,
        )
    }

    @Test
    fun `CSRF_EXEMPT has exact contents`() {
        assertEquals(
            setOf("/v1/auth/login", "/v1/auth/refresh", "/v1/auth/csrf-token"),
            AuthPaths.CSRF_EXEMPT,
        )
    }

    @Test
    fun `2fa verify is not CSRF exempt`() {
        assertFalse(AuthPaths.TOTP_VERIFY in AuthPaths.CSRF_EXEMPT)
        assertFalse(AuthPaths.TOTP_VERIFY_ALIAS in AuthPaths.CSRF_EXEMPT)
    }

    @Test
    fun `COOKIE_SAVE has exact contents`() {
        assertEquals(
            setOf(
                "/v1/auth/login",
                "/v1/auth/refresh",
                "/v1/auth/2fa/verify",
                "/v1/auth/verify-2fa",
            ),
            AuthPaths.COOKIE_SAVE,
        )
    }

    @Test
    fun `VPN_EXCLUDED keeps every historical entry and is within PUBLIC`() {
        // Entrées de l'ancien VpnRequiredInterceptor.VPN_EXCLUDED_PATHS — aucune ne doit disparaître
        val historical = setOf(
            "/v1/auth/login",
            "/v1/auth/refresh",
            "/v1/auth/2fa/verify",
            "/v1/auth/csrf-token",
            "/csrf-token",
        )
        assertTrue(AuthPaths.VPN_EXCLUDED.containsAll(historical))
        assertTrue(AuthPaths.PUBLIC.containsAll(AuthPaths.VPN_EXCLUDED))
    }

    @Test
    fun `register is not a public app path`() {
        assertFalse("/v1/auth/register" in AuthPaths.PUBLIC)
    }

    @Test
    fun `CSRF_EXEMPT and COOKIE_SAVE are subsets of PUBLIC`() {
        assertTrue(AuthPaths.PUBLIC.containsAll(AuthPaths.CSRF_EXEMPT))
        assertTrue(AuthPaths.PUBLIC.containsAll(AuthPaths.COOKIE_SAVE))
    }

    @Test
    fun `isSensitive flags auth, csrf and fcm paths only`() {
        assertTrue(AuthPaths.isSensitive(AuthPaths.LOGIN))
        assertTrue(AuthPaths.isSensitive(AuthPaths.TOTP_VERIFY))
        assertTrue(AuthPaths.isSensitive("/v1/auth/ws-token"))
        assertTrue(AuthPaths.isSensitive(AuthPaths.CSRF_LEGACY))
        assertTrue(AuthPaths.isSensitive("/v1/notifications/fcm-token"))
        assertFalse(AuthPaths.isSensitive("/v1/portfolios"))
        assertFalse(AuthPaths.isSensitive("/v1/market-data/quote/AAPL"))
    }
}
