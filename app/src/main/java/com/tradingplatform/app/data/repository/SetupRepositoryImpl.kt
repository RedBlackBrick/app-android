package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.domain.repository.SetupRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SetupRepositoryImpl @Inject constructor(
    private val dataStore: EncryptedDataStore,
) : SetupRepository {

    override suspend fun markSetupCompleted() {
        dataStore.writeBoolean(DataStoreKeys.SETUP_COMPLETED, true)
    }

    // readBoolean() already wraps IOException/GeneralSecurityException/SecurityException and
    // returns null on corruption or "never written" — both fall back to false here, which is
    // the safe default (worst case: the user re-does the onboarding QR scan).
    override suspend fun isSetupCompleted(): Boolean =
        dataStore.readBoolean(DataStoreKeys.SETUP_COMPLETED) == true
}
