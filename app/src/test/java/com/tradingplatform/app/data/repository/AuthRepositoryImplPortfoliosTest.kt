package com.tradingplatform.app.data.repository

import com.squareup.moshi.Moshi
import com.tradingplatform.app.data.api.AuthApi
import com.tradingplatform.app.data.api.interceptor.CsrfInterceptor
import com.tradingplatform.app.data.api.interceptor.EncryptedCookieJar
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.data.session.SessionManager
import com.tradingplatform.app.data.session.TokenHolder
import com.tradingplatform.app.domain.model.Portfolio
import com.tradingplatform.app.domain.repository.PortfolioSelectionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `AuthRepositoryImpl.getPortfolios()` délègue à [PortfolioSelectionRepository.refresh] :
 * il ne parle plus lui-même à l'API et ne réécrit plus `PORTFOLIO_ID` avec `portfolios[0]`
 * (la sélection valide est conservée entre deux sessions du même compte).
 */
class AuthRepositoryImplPortfoliosTest {

    // Non relaxés : tout appel inattendu à l'API ou au DataStore fait échouer le test.
    private val authApi = mockk<AuthApi>()
    private val dataStore = mockk<EncryptedDataStore>()
    private val selection = mockk<PortfolioSelectionRepository>()

    private val repository = AuthRepositoryImpl(
        authApi = authApi,
        tokenHolder = mockk<TokenHolder>(relaxed = true),
        dataStore = dataStore,
        moshi = Moshi.Builder().build(),
        csrfInterceptor = mockk<CsrfInterceptor>(relaxed = true),
        cookieJar = mockk<EncryptedCookieJar>(relaxed = true),
        okHttpClient = OkHttpClient(),
        sessionManager = mockk<SessionManager>(relaxed = true),
        portfolioSelectionRepository = selection,
    )

    private val alpha = Portfolio(id = "A", name = "Alpha", currency = "EUR")
    private val beta = Portfolio(id = "B", name = "Beta", currency = "USD")

    @Test
    fun `getPortfolios returns the list refreshed by the selection repository`() = runTest {
        coEvery { selection.refresh() } returns Result.success(listOf(alpha, beta))

        val result = repository.getPortfolios()

        assertEquals(listOf(alpha, beta), result.getOrThrow())
        coVerify(exactly = 1) { selection.refresh() }
        coVerify(exactly = 0) { authApi.getPortfolios() }
    }

    @Test
    fun `getPortfolios never overwrites the persisted portfolio id with the first portfolio`() = runTest {
        coEvery { selection.refresh() } returns Result.success(listOf(alpha, beta))

        repository.getPortfolios()

        coVerify(exactly = 0) { dataStore.writeString(DataStoreKeys.PORTFOLIO_ID, any()) }
    }

    @Test
    fun `getPortfolios propagates the No portfolio found failure`() = runTest {
        coEvery { selection.refresh() } returns Result.failure(IllegalStateException("No portfolio found"))

        val failure = repository.getPortfolios().exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("No portfolio found", failure?.message)
    }
}
