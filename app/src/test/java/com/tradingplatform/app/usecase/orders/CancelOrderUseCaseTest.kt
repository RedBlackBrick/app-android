package com.tradingplatform.app.usecase.orders

import com.tradingplatform.app.domain.exception.HttpStatusException
import com.tradingplatform.app.domain.model.WriteOutcome
import com.tradingplatform.app.domain.repository.OrdersRepository
import com.tradingplatform.app.domain.usecase.orders.CancelOrderUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class CancelOrderUseCaseTest {
    private val repository = mockk<OrdersRepository>()
    private lateinit var useCase: CancelOrderUseCase

    @Before
    fun setUp() {
        useCase = CancelOrderUseCase(repository)
    }

    @Test
    fun `delegates the order id and returns a confirmed outcome`() = runTest {
        coEvery { repository.cancelOrder(48211L) } returns Result.success(WriteOutcome.CONFIRMED)

        val result = useCase(48211L)

        assertEquals(WriteOutcome.CONFIRMED, result.getOrNull())
        coVerify(exactly = 1) { repository.cancelOrder(48211L) }
    }

    @Test
    fun `propagates the unconfirmed outcome untouched`() = runTest {
        coEvery { repository.cancelOrder(7L) } returns Result.success(WriteOutcome.REQUESTED_UNCONFIRMED)

        val result = useCase(7L)

        assertEquals(WriteOutcome.REQUESTED_UNCONFIRMED, result.getOrNull())
    }

    @Test
    fun `propagates a failure without retrying`() = runTest {
        val conflict = HttpStatusException(409, "v1/orders/{order_id}/cancel", "Ordre non annulable dans son état actuel")
        coEvery { repository.cancelOrder(48211L) } returns Result.failure(conflict)

        val result = useCase(48211L)

        assertSame(conflict, result.exceptionOrNull())
        coVerify(exactly = 1) { repository.cancelOrder(48211L) }
    }
}
