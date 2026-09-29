package com.tradingplatform.app.usecase.notification

import com.tradingplatform.app.domain.model.NotifCategory
import com.tradingplatform.app.domain.model.WriteOutcome
import com.tradingplatform.app.domain.repository.NotificationPreferencesRepository
import com.tradingplatform.app.domain.usecase.notification.SetPushCategoryUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetPushCategoryUseCaseTest {
    private val repository = mockk<NotificationPreferencesRepository>()
    private val useCase = SetPushCategoryUseCase(repository)

    @Test
    fun `forwards the category and the new push state`() = runTest {
        coEvery { repository.setPushEnabled(NotifCategory.RISK_ALERT, false) } returns
            Result.success(WriteOutcome.CONFIRMED)

        assertEquals(WriteOutcome.CONFIRMED, useCase(NotifCategory.RISK_ALERT, false).getOrThrow())
        coVerify(exactly = 1) { repository.setPushEnabled(NotifCategory.RISK_ALERT, false) }
    }

    @Test
    fun `passes REQUESTED_UNCONFIRMED through`() = runTest {
        coEvery { repository.setPushEnabled(any(), any()) } returns
            Result.success(WriteOutcome.REQUESTED_UNCONFIRMED)

        assertEquals(WriteOutcome.REQUESTED_UNCONFIRMED, useCase(NotifCategory.SYSTEM, true).getOrThrow())
    }

    @Test
    fun `propagates a repository failure`() = runTest {
        coEvery { repository.setPushEnabled(any(), any()) } returns Result.failure(RuntimeException("HTTP 422"))

        assertTrue(useCase(NotifCategory.STRATEGY_SIGNAL, true).isFailure)
    }
}
