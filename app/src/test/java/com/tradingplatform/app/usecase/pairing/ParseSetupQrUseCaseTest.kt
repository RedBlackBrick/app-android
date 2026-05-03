package com.tradingplatform.app.usecase.pairing

import com.tradingplatform.app.domain.usecase.pairing.ExpiredQrException
import com.tradingplatform.app.domain.usecase.pairing.MalformedQrException
import com.tradingplatform.app.domain.usecase.pairing.ParseSetupQrUseCase
import com.tradingplatform.app.domain.usecase.pairing.UnrecognizedQrException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class ParseSetupQrUseCaseTest {

    private lateinit var useCase: ParseSetupQrUseCase

    @Before
    fun setUp() {
        useCase = ParseSetupQrUseCase()
    }

    private fun futureIso(plusSeconds: Long = 300): String =
        Instant.now().plusSeconds(plusSeconds).truncatedTo(ChronoUnit.SECONDS).toString()

    private fun validQr(
        version: Int = 1,
        expiresAt: String = futureIso(),
    ): String = """
        {
          "v": $version,
          "type": "mobile_provisioning",
          "provisioning_id": "0123456789abcdef0123456789abcdef",
          "claim_token": "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCdE",
          "nonce": "deadbeef01234567890abcdef01234567890abcdef01234567890abcdef01234",
          "vps_endpoint": "vps.example.com:51820",
          "vps_pubkey": "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopq=",
          "dns": "10.42.0.1",
          "expires_at": "$expiresAt"
        }
    """.trimIndent()

    // ── Happy path ──────────────────────────────────────────────────────────

    @Test
    fun `valid v1 QR returns SetupQrData`() {
        val result = useCase(validQr())
        assertTrue(result.isSuccess)
        val data = result.getOrThrow()
        assertEquals(1, data.version)
        assertEquals("0123456789abcdef0123456789abcdef", data.provisioningId)
        assertEquals(43, data.claimToken.length)
        assertEquals(64, data.nonce.length)
        assertEquals("vps.example.com:51820", data.vpsEndpoint)
        assertEquals("10.42.0.1", data.dns)
    }

    @Test
    fun `empty dns is accepted (phone keeps LAN DNS)`() {
        val raw = validQr().replace("\"dns\": \"10.42.0.1\"", "\"dns\": \"\"")
        val result = useCase(raw)
        assertTrue(result.isSuccess)
        assertEquals("", result.getOrThrow().dns)
    }

    // ── Discriminator + version ─────────────────────────────────────────────

    @Test
    fun `non-JSON input is unrecognized`() {
        val result = useCase("pairing://radxa?id=123")
        assertTrue(result.exceptionOrNull() is UnrecognizedQrException)
    }

    @Test
    fun `wrong type is unrecognized`() {
        val raw = validQr().replace("mobile_provisioning", "radxa_pairing")
        val result = useCase(raw)
        assertTrue(result.exceptionOrNull() is UnrecognizedQrException)
    }

    @Test
    fun `unknown schema version is malformed`() {
        val raw = validQr(version = 2)
        val result = useCase(raw)
        assertTrue(result.exceptionOrNull() is MalformedQrException)
    }

    // ── Field validation ────────────────────────────────────────────────────

    @Test
    fun `claim_token of wrong length is malformed`() {
        val raw = validQr().replace(
            "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCdE",
            "tooshort",
        )
        val result = useCase(raw)
        assertTrue(result.exceptionOrNull() is MalformedQrException)
    }

    @Test
    fun `nonce with non-hex char is malformed`() {
        val raw = validQr().replace(
            "deadbeef01234567890abcdef01234567890abcdef01234567890abcdef01234",
            "ZZZZZZZZ01234567890abcdef01234567890abcdef01234567890abcdef01234",
        )
        val result = useCase(raw)
        assertTrue(result.exceptionOrNull() is MalformedQrException)
    }

    @Test
    fun `vps_pubkey of wrong length is malformed`() {
        val raw = validQr().replace(
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopq=",
            "shortkey=",
        )
        val result = useCase(raw)
        assertTrue(result.exceptionOrNull() is MalformedQrException)
    }

    @Test
    fun `vps_endpoint without port is malformed`() {
        val raw = validQr().replace("vps.example.com:51820", "vps.example.com")
        val result = useCase(raw)
        assertTrue(result.exceptionOrNull() is MalformedQrException)
    }

    @Test
    fun `dns that is not IPv4 is malformed`() {
        val raw = validQr().replace("\"dns\": \"10.42.0.1\"", "\"dns\": \"not-an-ip\"")
        val result = useCase(raw)
        assertTrue(result.exceptionOrNull() is MalformedQrException)
    }

    @Test
    fun `missing provisioning_id is malformed`() {
        val raw = validQr().replace("\"provisioning_id\": \"0123456789abcdef0123456789abcdef\",", "")
        val result = useCase(raw)
        assertTrue(result.exceptionOrNull() is MalformedQrException)
    }

    // ── Expiry ──────────────────────────────────────────────────────────────

    @Test
    fun `already-expired QR is rejected`() {
        val raw = validQr(expiresAt = "2020-01-01T00:00:00Z")
        val result = useCase(raw)
        assertTrue(result.exceptionOrNull() is ExpiredQrException)
    }

    @Test
    fun `malformed expires_at is malformed`() {
        val raw = validQr(expiresAt = "not-a-date")
        val result = useCase(raw)
        assertTrue(result.exceptionOrNull() is MalformedQrException)
    }
}
