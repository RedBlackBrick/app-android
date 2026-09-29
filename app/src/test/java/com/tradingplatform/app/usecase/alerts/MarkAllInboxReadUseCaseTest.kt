package com.tradingplatform.app.usecase.alerts

import com.tradingplatform.app.domain.repository.InboxRepository
import com.tradingplatform.app.domain.usecase.alerts.MarkAllInboxReadUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkAllInboxReadUseCaseTest {
    private val repository = mockk<InboxRepository>()
    private val useCase = MarkAllInboxReadUseCase(repository)

    @Test
    fun `marks every notification as read`() = runTest {
        coEvery { repository.markAllRead() } returns Result.success(Unit)

        assertTrue(useCase().isSuccess)
        coVerify(exactly = 1) { repository.markAllRead() }
    }

    @Test
    fun `propagates a repository failure`() = runTest {
        coEvery { repository.markAllRead() } returns Result.failure(RuntimeException("HTTP 500"))

        assertTrue(useCase().isFailure)
    }
}
