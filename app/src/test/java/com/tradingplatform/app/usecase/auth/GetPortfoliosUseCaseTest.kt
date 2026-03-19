package com.tradingplatform.app.usecase.auth

import com.tradingplatform.app.domain.model.Portfolio
import com.tradingplatform.app.domain.repository.AuthRepository
import com.tradingplatform.app.domain.usecase.auth.GetPortfoliosUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GetPortfoliosUseCaseTest {
    private val authRepository = mockk<AuthRepository>()
    private lateinit var useCase: GetPortfoliosUseCase

    private val fakePortfolio = Portfolio(id = "42", name = "Main Portfolio", currency = "EUR")

    @Before
    fun setUp() {
        useCase = GetPortfoliosUseCase(authRepository)
    }

    @Test
    fun `single portfolio is propagated`() = runTest {
        coEvery { authRepository.getPortfolios() } returns Result.success(listOf(fakePortfolio))

        val result = useCase()

        assertTrue(result.isSuccess)
        assertEquals(listOf(fakePortfolio), result.getOrThrow())
    }

    @Test
    fun `multiple portfolios are propagated`() = runTest {
        val second = Portfolio(id = "99", name = "Secondary", currency = "USD")
        coEvery { authRepository.getPortfolios() } returns Result.success(listOf(fakePortfolio, second))

        val result = useCase()

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrThrow().size)
    }

    @Test
    fun `empty portfolio list is propagated`() = runTest {
        coEvery { authRepository.getPortfolios() } returns Result.success(emptyList())

        val result = useCase()

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
    }

    @Test
    fun `repository failure is propagated`() = runTest {
        coEvery { authRepository.getPortfolios() } returns Result.failure(RuntimeException("401"))

        val result = useCase()

        assertTrue(result.isFailure)
    }
}
