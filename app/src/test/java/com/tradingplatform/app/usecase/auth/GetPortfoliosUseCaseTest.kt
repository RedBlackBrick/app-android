package com.tradingplatform.app.usecase.auth

import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.domain.model.Portfolio
import com.tradingplatform.app.domain.repository.AuthRepository
import com.tradingplatform.app.domain.usecase.auth.GetPortfoliosUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GetPortfoliosUseCaseTest {
    private val authRepository = mockk<AuthRepository>()
    private val dataStore = mockk<EncryptedDataStore>(relaxed = true)
    private lateinit var useCase: GetPortfoliosUseCase

    private val fakePortfolio = Portfolio(id = "42", name = "Main Portfolio", currency = "EUR")

    @Before
    fun setUp() {
        useCase = GetPortfoliosUseCase(authRepository, dataStore)
    }

    @Test
    fun `single portfolio stores its id`() = runTest {
        coEvery { authRepository.getPortfolios() } returns Result.success(listOf(fakePortfolio))

        val result = useCase()

        assertTrue(result.isSuccess)
        coVerify { dataStore.writeString(DataStoreKeys.PORTFOLIO_ID, "42") }
    }

    @Test
    fun `multiple portfolios stores first and returns list`() = runTest {
        val second = Portfolio(id = "99", name = "Secondary", currency = "USD")
        coEvery { authRepository.getPortfolios() } returns Result.success(listOf(fakePortfolio, second))

        val result = useCase()

        assertTrue(result.isSuccess)
        // Must use portfolios[0] when multiple
        coVerify { dataStore.writeString(DataStoreKeys.PORTFOLIO_ID, "42") }
    }

    @Test
    fun `empty portfolio list does not write to datastore`() = runTest {
        coEvery { authRepository.getPortfolios() } returns Result.success(emptyList())

        val result = useCase()

        assertTrue(result.isSuccess)
        coVerify(exactly = 0) { dataStore.writeString(any(), any()) }
    }

    @Test
    fun `repository failure is propagated`() = runTest {
        coEvery { authRepository.getPortfolios() } returns Result.failure(RuntimeException("401"))

        val result = useCase()

        assertTrue(result.isFailure)
    }
}
