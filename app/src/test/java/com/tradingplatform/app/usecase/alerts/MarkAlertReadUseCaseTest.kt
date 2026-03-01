package com.tradingplatform.app.usecase.alerts

import com.tradingplatform.app.domain.repository.AlertRepository
import com.tradingplatform.app.domain.usecase.alerts.MarkAlertReadUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MarkAlertReadUseCaseTest {
    private val repository = mockk<AlertRepository>()
    private lateinit var useCase: MarkAlertReadUseCase

    @Before
    fun setUp() {
        useCase = MarkAlertReadUseCase(repository)
    }

    @Test
    fun `marks alert as read on success`() = runTest {
        coEvery { repository.markRead(42L) } returns Result.success(Unit)

        val result = useCase(42L)

        assertTrue(result.isSuccess)
        coVerify { repository.markRead(42L) }
    }

    @Test
    fun `returns failure when repository fails`() = runTest {
        coEvery { repository.markRead(any()) } returns Result.failure(RuntimeException("DB error"))

        val result = useCase(1L)

        assertTrue(result.isFailure)
    }
}
