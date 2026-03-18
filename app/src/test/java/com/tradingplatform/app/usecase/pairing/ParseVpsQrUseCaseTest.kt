package com.tradingplatform.app.usecase.pairing

import com.tradingplatform.app.domain.usecase.pairing.MalformedQrException
import com.tradingplatform.app.domain.usecase.pairing.ParseVpsQrUseCase
import com.tradingplatform.app.domain.usecase.pairing.UnrecognizedQrException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ParseVpsQrUseCaseTest {
    private lateinit var useCase: ParseVpsQrUseCase

    @Before
    fun setUp() {
        useCase = ParseVpsQrUseCase()
    }

    @Test
    fun `valid VPS QR returns PairingSession`() = runTest {
        val raw = """{"session_id":"abc-123","session_pin":"472938","device_wg_ip":"10.42.0.5","local_token":"tok-xyz","nonce":"deadbeef01234567890abcdef01234567890abcdef01234567890abcdef012345"}"""
        val result = useCase(raw)

        assertTrue(result.isSuccess)
        val session = result.getOrThrow()
        assertEquals("abc-123", session.sessionId)
        assertEquals("472938", session.sessionPin)
        assertEquals("10.42.0.5", session.deviceWgIp)
        assertEquals("tok-xyz", session.localToken)
        assertEquals("deadbeef01234567890abcdef01234567890abcdef01234567890abcdef012345", session.nonce)
    }

    @Test
    fun `non-JSON input returns UnrecognizedQrException`() = runTest {
        val result = useCase("pairing://radxa?id=123")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is UnrecognizedQrException)
    }

    @Test
    fun `missing session_pin returns MalformedQrException`() = runTest {
        val raw = """{"session_id":"abc-123","device_wg_ip":"10.42.0.5","local_token":"tok-xyz","nonce":"aabbcc"}"""
        val result = useCase(raw)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MalformedQrException)
    }

    @Test
    fun `missing session_id returns MalformedQrException`() = runTest {
        val raw = """{"session_pin":"472938","device_wg_ip":"10.42.0.5","local_token":"tok-xyz","nonce":"aabbcc"}"""
        val result = useCase(raw)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MalformedQrException)
    }

    @Test
    fun `missing device_wg_ip returns MalformedQrException`() = runTest {
        val raw = """{"session_id":"abc-123","session_pin":"472938","local_token":"tok-xyz","nonce":"aabbcc"}"""
        val result = useCase(raw)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MalformedQrException)
    }

    @Test
    fun `missing local_token returns MalformedQrException`() = runTest {
        val raw = """{"session_id":"abc-123","session_pin":"472938","device_wg_ip":"10.42.0.5","nonce":"aabbcc"}"""
        val result = useCase(raw)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MalformedQrException)
    }

    @Test
    fun `missing nonce returns MalformedQrException`() = runTest {
        val raw = """{"session_id":"abc-123","session_pin":"472938","device_wg_ip":"10.42.0.5","local_token":"tok-xyz"}"""
        val result = useCase(raw)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MalformedQrException)
    }

    @Test
    fun `empty JSON object returns failure`() = runTest {
        val result = useCase("{}")
        assertTrue(result.isFailure)
    }

    @Test
    fun `invalid JSON returns UnrecognizedQrException`() = runTest {
        val result = useCase("{not valid json}")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is UnrecognizedQrException)
    }

    @Test
    fun `whitespace trimmed before parsing`() = runTest {
        val raw = """  {"session_id":"abc-123","session_pin":"472938","device_wg_ip":"10.42.0.5","local_token":"tok-xyz","nonce":"aabbcc"}  """
        val result = useCase(raw)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `empty string input returns UnrecognizedQrException`() = runTest {
        val result = useCase("")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is UnrecognizedQrException)
    }
}
