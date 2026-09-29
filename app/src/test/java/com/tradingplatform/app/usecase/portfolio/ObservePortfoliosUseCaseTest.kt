package com.tradingplatform.app.usecase.portfolio

import com.tradingplatform.app.domain.model.Portfolio
import com.tradingplatform.app.domain.repository.PortfolioSelectionRepository
import com.tradingplatform.app.domain.usecase.portfolio.ObservePortfoliosUseCase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class ObservePortfoliosUseCaseTest {

    private val alpha = Portfolio(id = "A", name = "Alpha", currency = "EUR")
    private val beta = Portfolio(id = "B", name = "Beta", currency = "USD")

    private val knownPortfolios = MutableStateFlow(emptyList<Portfolio>())
    private val repo = mockk<PortfolioSelectionRepository> {
        every { portfolios } returns knownPortfolios
    }
    private val useCase = ObservePortfoliosUseCase(repo)

    @Test
    fun `exposes an empty list before the first refresh`() {
        assertEquals(emptyList<Portfolio>(), useCase().value)
    }

    @Test
    fun `exposes the latest known portfolios`() {
        knownPortfolios.value = listOf(alpha, beta)

        assertEquals(listOf(alpha, beta), useCase().value)
    }
}
