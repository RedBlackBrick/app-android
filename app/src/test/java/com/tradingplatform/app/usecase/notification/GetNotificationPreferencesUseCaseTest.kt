package com.tradingplatform.app.usecase.notification

import com.tradingplatform.app.domain.model.ChannelPreferences
import com.tradingplatform.app.domain.model.NotifCategory
import com.tradingplatform.app.domain.model.NotificationPreferences
import com.tradingplatform.app.domain.repository.NotificationPreferencesRepository
import com.tradingplatform.app.domain.usecase.notification.GetNotificationPreferencesUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GetNotificationPreferencesUseCaseTest {
    private val repository = mockk<NotificationPreferencesRepository>()
    private val useCase = GetNotificationPreferencesUseCase(repository)

    @Test
    fun `returns the account preferences`() = runTest {
        val prefs = NotificationPreferences(
            categories = mapOf(
                NotifCategory.STRATEGY_SIGNAL to ChannelPreferences(inApp = true, push = true, email = false),
                NotifCategory.RISK_ALERT to ChannelPreferences(),
                NotifCategory.SYSTEM to ChannelPreferences(push = false),
            ),
        )
        coEvery { repository.get() } returns Result.success(prefs)

        assertEquals(prefs, useCase().getOrThrow())
    }

    @Test
    fun `propagates a repository failure`() = runTest {
        coEvery { repository.get() } returns Result.failure(RuntimeException("HTTP 500"))

        assertTrue(useCase().isFailure)
    }
}
