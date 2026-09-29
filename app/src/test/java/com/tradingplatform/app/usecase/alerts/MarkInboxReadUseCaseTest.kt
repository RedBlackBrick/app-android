package com.tradingplatform.app.usecase.alerts

import com.tradingplatform.app.domain.repository.InboxRepository
import com.tradingplatform.app.domain.usecase.alerts.MarkInboxReadUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkInboxReadUseCaseTest {
    private val repository = mockk<InboxRepository>()
    private val useCase = MarkInboxReadUseCase(repository)

    @Test
    fun `marks the given notification as read`() = runTest {
        coEvery { repository.markRead("n-42") } returns Result.success(Unit)

        assertTrue(useCase("n-42").isSuccess)
        coVerify(exactly = 1) { repository.markRead("n-42") }
    }

    @Test
    fun `propagates a repository failure`() = runTest {
        coEvery { repository.markRead(any()) } returns Result.failure(RuntimeException("HTTP 404"))

        assertTrue(useCase("n-42").isFailure)
    }
}
