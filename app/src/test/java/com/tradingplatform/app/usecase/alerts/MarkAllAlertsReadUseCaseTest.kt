package com.tradingplatform.app.usecase.alerts

import com.tradingplatform.app.domain.repository.AlertRepository
import com.tradingplatform.app.domain.usecase.alerts.MarkAllAlertsReadUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MarkAllAlertsReadUseCaseTest {
    private val repository = mockk<AlertRepository>()
    private lateinit var useCase: MarkAllAlertsReadUseCase

    @Before
    fun setUp() {
        useCase = MarkAllAlertsReadUseCase(repository)
    }

    @Test
    fun `delegates to the repository and returns success`() = runTest {
        coEvery { repository.markAllRead() } returns Result.success(Unit)

        val result = useCase()

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { repository.markAllRead() }
    }

    @Test
    fun `propagates the repository failure unchanged`() = runTest {
        val error = RuntimeException("DB error")
        coEvery { repository.markAllRead() } returns Result.failure(error)

        val result = useCase()

        assertTrue(result.isFailure)
        assertEquals(error, result.exceptionOrNull())
    }
}
