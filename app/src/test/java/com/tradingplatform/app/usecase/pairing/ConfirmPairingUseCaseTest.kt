package com.tradingplatform.app.usecase.pairing

import com.tradingplatform.app.domain.model.PairingStatus
import com.tradingplatform.app.domain.repository.PairingRepository
import com.tradingplatform.app.domain.usecase.pairing.ConfirmPairingUseCase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConfirmPairingUseCaseTest {
    private val repository = mockk<PairingRepository>()
    private lateinit var useCase: ConfirmPairingUseCase

    @Before
    fun setUp() {
        useCase = ConfirmPairingUseCase(repository)
    }

    @Test
    fun `returns PAIRED when flow emits PAIRED`() = runTest {
        every {
            repository.pollStatus("192.168.1.42", 8099, "abc-123")
        } returns flowOf(PairingStatus.PENDING, PairingStatus.PAIRED)

        val result = useCase("192.168.1.42", 8099, "abc-123")

        assertTrue(result.isSuccess)
        assertEquals(PairingStatus.PAIRED, result.getOrThrow())
    }

    @Test
    fun `returns FAILED when flow emits FAILED`() = runTest {
        every {
            repository.pollStatus(any(), any(), any())
        } returns flowOf(PairingStatus.PENDING, PairingStatus.FAILED)

        val result = useCase("192.168.1.42", 8099, "abc-123")

        assertTrue(result.isSuccess)
        assertEquals(PairingStatus.FAILED, result.getOrThrow())
    }

    @Test
    fun `stops at first terminal status — does not consume further emissions`() = runTest {
        every {
            repository.pollStatus(any(), any(), any())
        } returns flow {
            emit(PairingStatus.PENDING)
            emit(PairingStatus.PAIRED)
            // This should never be reached — flow.first() cancels after first match
            emit(PairingStatus.FAILED)
        }

        val result = useCase("192.168.1.42", 8099, "abc-123")

        assertTrue(result.isSuccess)
        assertEquals(PairingStatus.PAIRED, result.getOrThrow())
    }

    @Test
    fun `returns failure when flow throws exception`() = runTest {
        every {
            repository.pollStatus(any(), any(), any())
        } returns flow { throw RuntimeException("Network error") }

        val result = useCase("192.168.1.42", 8099, "abc-123")

        assertTrue(result.isFailure)
    }
}
