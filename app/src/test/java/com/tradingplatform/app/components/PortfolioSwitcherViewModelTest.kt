package com.tradingplatform.app.components

import com.tradingplatform.app.domain.model.Portfolio
import com.tradingplatform.app.domain.usecase.portfolio.ObserveActivePortfolioUseCase
import com.tradingplatform.app.domain.usecase.portfolio.ObservePortfoliosUseCase
import com.tradingplatform.app.domain.usecase.portfolio.SelectPortfolioUseCase
import com.tradingplatform.app.ui.components.PortfolioSwitcherViewModel
import com.tradingplatform.app.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PortfolioSwitcherViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val main = Portfolio(id = "p1", name = "Compte principal", currency = "EUR")
    private val pea = Portfolio(id = "p2", name = "PEA", currency = "EUR")

    private val portfoliosSource = MutableStateFlow(listOf(main, pea))
    private val activeSource = MutableStateFlow("p1")

    private val observePortfolios = mockk<ObservePortfoliosUseCase>()
    private val observeActivePortfolio = mockk<ObserveActivePortfolioUseCase>()
    private val selectPortfolio = mockk<SelectPortfolioUseCase>()

    @Before
    fun setUp() {
        every { observePortfolios() } returns portfoliosSource
        every { observeActivePortfolio() } returns activeSource
    }

    private fun createViewModel() = PortfolioSwitcherViewModel(
        observePortfoliosUseCase = observePortfolios,
        observeActivePortfolioUseCase = observeActivePortfolio,
        selectPortfolioUseCase = selectPortfolio,
    )

    @Test
    fun `portfolios exposes the account portfolios and follows their changes`() = runTest {
        val viewModel = createViewModel()

        assertEquals(listOf(main, pea), viewModel.portfolios.value)

        portfoliosSource.value = listOf(main)

        assertEquals(listOf(main), viewModel.portfolios.value)
    }

    @Test
    fun `activeId follows the active portfolio`() = runTest {
        val viewModel = createViewModel()

        assertEquals("p1", viewModel.activeId.value)

        activeSource.value = "p2"

        assertEquals("p2", viewModel.activeId.value)
    }

    @Test
    fun `select delegates to the use case exactly once with the chosen id`() = runTest {
        coEvery { selectPortfolio("p2") } returns Result.success(Unit)
        val viewModel = createViewModel()

        viewModel.select("p2")

        coVerify(exactly = 1) { selectPortfolio("p2") }
        coVerify(exactly = 0) { selectPortfolio("p1") }
    }

    @Test
    fun `a failed selection does not throw and leaves the active portfolio untouched`() = runTest {
        coEvery { selectPortfolio("unknown") } returns
            Result.failure<Unit>(IllegalArgumentException("Portfolio not found"))
        val viewModel = createViewModel()

        viewModel.select("unknown")

        coVerify(exactly = 1) { selectPortfolio("unknown") }
        assertEquals("p1", viewModel.activeId.value)
        assertEquals(listOf(main, pea), viewModel.portfolios.value)
    }

    @Test
    fun `activeId is null while the use case has not emitted an id`() = runTest {
        every { observeActivePortfolio() } returns emptyFlow()

        val viewModel = createViewModel()

        assertNull(viewModel.activeId.value)
    }
}
