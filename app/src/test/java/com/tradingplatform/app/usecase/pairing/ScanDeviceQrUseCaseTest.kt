package com.tradingplatform.app.usecase.pairing

import com.tradingplatform.app.domain.usecase.pairing.MalformedQrException
import com.tradingplatform.app.domain.usecase.pairing.ScanDeviceQrUseCase
import com.tradingplatform.app.domain.usecase.pairing.UnrecognizedQrException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScanDeviceQrUseCaseTest {
    private lateinit var useCase: ScanDeviceQrUseCase

    @Before
    fun setUp() {
        useCase = ScanDeviceQrUseCase()
    }

    // 44-char base64 WireGuard public key (valid length)
    private val validPubkey = "A".repeat(44)

    @Test
    fun `valid Radxa QR returns DevicePairingInfo`() = runTest {
        val raw = "pairing://radxa?id=device-001&pub=$validPubkey&ip=192.168.1.42&port=8099"
        val result = useCase(raw)

        assertTrue(result.isSuccess)
        val info = result.getOrThrow()
        assertEquals("device-001", info.deviceId)
        assertEquals(validPubkey, info.wgPubkey)
        assertEquals("192.168.1.42", info.localIp)
        assertEquals(8099, info.port)
    }

    @Test
    fun `wrong scheme returns UnrecognizedQrException`() = runTest {
        val raw = "https://example.com?id=x&pub=$validPubkey&ip=192.168.1.1&port=8099"
        val result = useCase(raw)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is UnrecognizedQrException)
    }

    @Test
    fun `wrong host returns UnrecognizedQrException`() = runTest {
        val raw = "pairing://vps?id=x&pub=$validPubkey&ip=192.168.1.1&port=8099"
        val result = useCase(raw)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is UnrecognizedQrException)
    }

    @Test
    fun `wrong port returns MalformedQrException`() = runTest {
        val raw = "pairing://radxa?id=x&pub=$validPubkey&ip=192.168.1.1&port=9000"
        val result = useCase(raw)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MalformedQrException)
    }

    @Test
    fun `pubkey wrong length returns MalformedQrException`() = runTest {
        val raw = "pairing://radxa?id=x&pub=shortkey&ip=192.168.1.1&port=8099"
        val result = useCase(raw)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MalformedQrException)
    }

    @Test
    fun `hostname instead of IP returns MalformedQrException`() = runTest {
        val raw = "pairing://radxa?id=x&pub=$validPubkey&ip=radxa.local&port=8099"
        val result = useCase(raw)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MalformedQrException)
    }

    @Test
    fun `missing id param returns MalformedQrException`() = runTest {
        val raw = "pairing://radxa?pub=$validPubkey&ip=192.168.1.1&port=8099"
        val result = useCase(raw)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MalformedQrException)
    }

    @Test
    fun `missing pub param returns MalformedQrException`() = runTest {
        val raw = "pairing://radxa?id=device-001&ip=192.168.1.1&port=8099"
        val result = useCase(raw)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MalformedQrException)
    }

    @Test
    fun `missing ip param returns MalformedQrException`() = runTest {
        val raw = "pairing://radxa?id=device-001&pub=$validPubkey&port=8099"
        val result = useCase(raw)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MalformedQrException)
    }

    @Test
    fun `10-dot LAN IP is valid`() = runTest {
        val raw = "pairing://radxa?id=device-001&pub=$validPubkey&ip=10.42.0.5&port=8099"
        val result = useCase(raw)
        assertTrue(result.isSuccess)
        assertEquals("10.42.0.5", result.getOrThrow().localIp)
    }

    @Test
    fun `non-numeric port returns MalformedQrException`() = runTest {
        val raw = "pairing://radxa?id=x&pub=$validPubkey&ip=192.168.1.1&port=abcd"
        val result = useCase(raw)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MalformedQrException)
    }

    @Test
    fun `plain JSON input (VPS QR) returns UnrecognizedQrException`() = runTest {
        val result = useCase("""{"session_id":"abc","session_pin":"123","device_wg_ip":"10.0.0.1"}""")
        assertTrue(result.isFailure)
        // Parsed as URI with scheme null — not "pairing"
        assertTrue(result.exceptionOrNull() is UnrecognizedQrException)
    }
}
