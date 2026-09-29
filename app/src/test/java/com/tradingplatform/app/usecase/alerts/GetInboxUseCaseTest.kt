package com.tradingplatform.app.usecase.alerts

import com.tradingplatform.app.domain.model.InboxNotification
import com.tradingplatform.app.domain.repository.InboxRepository
import com.tradingplatform.app.domain.usecase.alerts.GetInboxUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GetInboxUseCaseTest {
    private val repository = mockk<InboxRepository>()
    private val useCase = GetInboxUseCase(repository)

    private val item = InboxNotification(
        id = "n-1",
        type = "risk_alert",
        title = "Drawdown à 12.4%",
        body = "Seuil dépassé",
        read = false,
        createdAt = Instant.parse("2026-09-29T08:14:03Z"),
    )

    @Test
    fun `returns the repository list with the default limit of 50`() = runTest {
        coEvery { repository.list(50) } returns Result.success(listOf(item))

        val result = useCase()

        assertSame(item, result.getOrThrow().single())
        coVerify(exactly = 1) { repository.list(50) }
    }

    @Test
    fun `forwards an explicit limit`() = runTest {
        coEvery { repository.list(20) } returns Result.success(emptyList())

        assertEquals(emptyList<InboxNotification>(), useCase(20).getOrThrow())
        coVerify(exactly = 1) { repository.list(20) }
    }

    @Test
    fun `propagates a repository failure`() = runTest {
        coEvery { repository.list(any()) } returns Result.failure(RuntimeException("HTTP 500"))

        assertTrue(useCase().isFailure)
    }
}
