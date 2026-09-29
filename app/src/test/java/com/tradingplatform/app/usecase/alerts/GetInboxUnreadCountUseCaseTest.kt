package com.tradingplatform.app.usecase.alerts

import com.tradingplatform.app.domain.repository.InboxRepository
import com.tradingplatform.app.domain.usecase.alerts.GetInboxUnreadCountUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GetInboxUnreadCountUseCaseTest {
    private val repository = mockk<InboxRepository>()
    private val useCase = GetInboxUnreadCountUseCase(repository)

    @Test
    fun `returns the unread count`() = runTest {
        coEvery { repository.unreadCount() } returns Result.success(3)

        assertEquals(3, useCase().getOrThrow())
    }

    @Test
    fun `propagates a repository failure`() = runTest {
        coEvery { repository.unreadCount() } returns Result.failure(RuntimeException("HTTP 500"))

        assertTrue(useCase().isFailure)
    }
}
