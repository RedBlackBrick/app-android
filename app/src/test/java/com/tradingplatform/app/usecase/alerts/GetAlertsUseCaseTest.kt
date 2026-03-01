package com.tradingplatform.app.usecase.alerts

import com.tradingplatform.app.domain.model.Alert
import com.tradingplatform.app.domain.model.AlertType
import com.tradingplatform.app.domain.repository.AlertRepository
import com.tradingplatform.app.domain.usecase.alerts.GetAlertsUseCase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.time.Instant

class GetAlertsUseCaseTest {
    private val repository = mockk<AlertRepository>()
    private lateinit var useCase: GetAlertsUseCase

    private val fakeAlert = Alert(
        id = 1L,
        title = "AAPL Alert",
        body = "Price target reached",
        type = AlertType.PRICE_ALERT,
        receivedAt = Instant.now(),
        read = false,
    )

    @Before
    fun setUp() {
        useCase = GetAlertsUseCase(repository)
    }

    @Test
    fun `returns flow of alerts`() = runTest {
        every { repository.getAlerts() } returns flowOf(listOf(fakeAlert))

        val alerts = useCase().first()

        assertEquals(1, alerts.size)
        assertEquals("AAPL Alert", alerts[0].title)
    }

    @Test
    fun `returns empty list when no alerts`() = runTest {
        every { repository.getAlerts() } returns flowOf(emptyList())

        val alerts = useCase().first()

        assertEquals(0, alerts.size)
    }

    @Test
    fun `returns unread alert correctly`() = runTest {
        every { repository.getAlerts() } returns flowOf(listOf(fakeAlert))

        val alerts = useCase().first()

        assertFalse(alerts[0].read)
        assertEquals(AlertType.PRICE_ALERT, alerts[0].type)
    }
}
