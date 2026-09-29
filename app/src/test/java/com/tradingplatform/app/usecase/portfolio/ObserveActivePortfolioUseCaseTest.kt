package com.tradingplatform.app.usecase.portfolio

import app.cash.turbine.test
import com.tradingplatform.app.domain.repository.PortfolioSelectionRepository
import com.tradingplatform.app.domain.usecase.portfolio.ObserveActivePortfolioUseCase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ObserveActivePortfolioUseCaseTest {

    private val activeId = MutableStateFlow<String?>(null)
    private val repo = mockk<PortfolioSelectionRepository> {
        every { activePortfolioId } returns activeId
    }
    private val useCase = ObserveActivePortfolioUseCase(repo)

    @Test
    fun `does not emit while the active portfolio is unknown`() = runTest {
        useCase().test {
            expectNoEvents()
        }
    }

    @Test
    fun `emits the active id once known then each change`() = runTest {
        useCase().test {
            expectNoEvents()

            activeId.value = "A"
            assertEquals("A", awaitItem())

            activeId.value = "B"
            assertEquals("B", awaitItem())
        }
    }

    @Test
    fun `emits the current id immediately to a late collector`() = runTest {
        activeId.value = "B"

        useCase().test {
            assertEquals("B", awaitItem())
            expectNoEvents()
        }
    }

    @Test
    fun `never emits null when the selection is cleared`() = runTest {
        activeId.value = "A"

        useCase().test {
            assertEquals("A", awaitItem())

            activeId.value = null
            expectNoEvents()

            activeId.value = "C"
            assertEquals("C", awaitItem())
        }
    }
}
