package com.tradingplatform.app.usecase.auth

import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.data.session.TokenHolder
import com.tradingplatform.app.domain.usecase.auth.GetAuthContextUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [GetAuthContextUseCase] — cold-start TokenHolder population (audit A-corr-2).
 */
class GetAuthContextUseCaseTest {

    private val dataStore = mockk<EncryptedDataStore>(relaxed = true)
    private val tokenHolder = TokenHolder()
    private lateinit var useCase: GetAuthContextUseCase

    @Before
    fun setUp() {
        coEvery { dataStore.readBoolean(DataStoreKeys.IS_ADMIN) } returns false
        coEvery { dataStore.readBoolean(DataStoreKeys.SETUP_COMPLETED) } returns true
        coEvery { dataStore.readString(DataStoreKeys.WG_PRIVATE_KEY) } returns "wg-key"
        useCase = GetAuthContextUseCase(dataStore, tokenHolder)
    }

    @Test
    fun `token on disk and empty holder populates the holder`() = runTest {
        coEvery { dataStore.readString(DataStoreKeys.ACCESS_TOKEN) } returns "disk-token"

        val context = useCase()

        assertTrue(context.isLoggedIn)
        assertEquals("disk-token", tokenHolder.accessToken)
    }

    @Test
    fun `holder already set is left untouched`() = runTest {
        // A refresh (or the TradingApplication preload) already wrote a fresher token.
        tokenHolder.setToken("fresh-token")
        coEvery { dataStore.readString(DataStoreKeys.ACCESS_TOKEN) } returns "disk-token"

        val context = useCase()

        assertTrue(context.isLoggedIn)
        assertEquals("fresh-token", tokenHolder.accessToken)
    }

    @Test
    fun `no token on disk leaves the holder empty`() = runTest {
        coEvery { dataStore.readString(DataStoreKeys.ACCESS_TOKEN) } returns null

        val context = useCase()

        assertFalse(context.isLoggedIn)
        assertNull(tokenHolder.accessToken)
    }
}
