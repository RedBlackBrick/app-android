package com.tradingplatform.app.domain.usecase.auth

import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import javax.inject.Inject

/**
 * Encapsulates reading the persisted portfolio ID from [EncryptedDataStore].
 *
 * Prevents ViewModels from depending directly on the data layer.
 * Returns 0 if no portfolio ID is stored (pre-login state).
 */
class GetPortfolioIdUseCase @Inject constructor(
    private val dataStore: EncryptedDataStore,
) {
    suspend operator fun invoke(): Int =
        dataStore.readInt(DataStoreKeys.PORTFOLIO_ID) ?: 0
}
