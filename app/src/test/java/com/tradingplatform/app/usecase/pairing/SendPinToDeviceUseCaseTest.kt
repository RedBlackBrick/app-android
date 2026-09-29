package com.tradingplatform.app.usecase.pairing

import com.tradingplatform.app.domain.repository.PairingRepository
import com.tradingplatform.app.domain.usecase.pairing.SendPinToDeviceUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SendPinToDeviceUseCaseTest {
    private val repository = mockk<PairingRepository>()
    private lateinit var useCase: SendPinToDeviceUseCase

    @Before
    fun setUp() {
        useCase = SendPinToDeviceUseCase(repository)
    }

    @Test
    fun `delegates to repository sendPin`() = runTest {
        coEvery { repository.sendPin(any(), any(), any(), any(), any(), any(), any()) } returns Result.success(Unit)

        val result = useCase(
            deviceIp = "192.168.1.42",
            devicePort = 8099,
            sessionId = "abc-123",
            sessionPin = "472938",
            localToken = "tok-xyz",
            nonce = "deadbeef0123456789abcdef",
            radxaWgPubkey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        )

        assertTrue(result.isSuccess)
        coVerify {
            repository.sendPin(
                deviceIp = "192.168.1.42",
                devicePort = 8099,
                sessionId = "abc-123",
                sessionPin = "472938",
                localToken = "tok-xyz",
                nonce = "deadbeef0123456789abcdef",
                radxaWgPubkey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            )
        }
    }

    @Test
    fun `returns failure when repository fails`() = runTest {
        coEvery { repository.sendPin(any(), any(), any(), any(), any(), any(), any()) } returns Result.failure(RuntimeException("Connection refused"))

        val result = useCase(
            deviceIp = "192.168.1.42",
            devicePort = 8099,
            sessionId = "abc-123",
            sessionPin = "472938",
            localToken = "tok-xyz",
            nonce = "deadbeef0123456789abcdef",
            radxaWgPubkey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun `does not call repository more than once`() = runTest {
        coEvery { repository.sendPin(any(), any(), any(), any(), any(), any(), any()) } returns Result.success(Unit)

        useCase(
            deviceIp = "192.168.1.42",
            devicePort = 8099,
            sessionId = "abc-123",
            sessionPin = "472938",
            localToken = "tok-xyz",
            nonce = "deadbeef0123456789abcdef",
            radxaWgPubkey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        )

        coVerify(exactly = 1) { repository.sendPin(any(), any(), any(), any(), any(), any(), any()) }
    }
}
