package com.tradingplatform.app.data.websocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `_check_origin` du backend compare l'en-tête à `WS_ALLOWED_ORIGINS` par égalité exacte
 * (minuscules, sans `/` final). Un navigateur omet le port par défaut : `https://10.42.0.1:443`
 * doit donc devenir `https://10.42.0.1`, sinon le handshake reste refusé.
 */
class WsOriginTest {

    @Test
    fun `default https port is omitted`() {
        assertEquals("https://10.42.0.1", wsOrigin("https://10.42.0.1:443", override = ""))
    }

    @Test
    fun `base url without an explicit port gives the same origin`() {
        assertEquals("https://10.42.0.1", wsOrigin("https://10.42.0.1/", override = ""))
    }

    @Test
    fun `default http port is omitted`() {
        assertEquals("http://example.test", wsOrigin("http://example.test:80/", override = ""))
    }

    @Test
    fun `non default port is kept`() {
        assertEquals("http://localhost:8080", wsOrigin("http://localhost:8080/api", override = ""))
    }

    @Test
    fun `host is lowercased`() {
        assertEquals("https://vps.example.com", wsOrigin("https://VPS.Example.com:443", override = ""))
    }

    @Test
    fun `explicit override wins over the base url`() {
        assertEquals("https://trading.example.com", wsOrigin("https://10.42.0.1:443", override = "https://trading.example.com"))
    }

    @Test
    fun `override is normalised like the backend does (lowercase, no trailing slash)`() {
        assertEquals("https://trading.example.com", wsOrigin("https://10.42.0.1", override = " HTTPS://Trading.Example.com/ "))
    }

    @Test
    fun `blank override falls back to the derived origin`() {
        assertEquals("https://10.42.0.1", wsOrigin("https://10.42.0.1:443", override = "   "))
    }

    @Test
    fun `unparseable base url yields no header`() {
        assertNull(wsOrigin("not a url", override = ""))
    }
}
