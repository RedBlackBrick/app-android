package com.tradingplatform.app.domain.usecase.auth

import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.data.local.db.AppDatabase
import com.tradingplatform.app.domain.repository.AuthRepository
import timber.log.Timber
import javax.inject.Inject

class LogoutUseCase @Inject constructor(
    private val authRepository: AuthRepository,
    private val dataStore: EncryptedDataStore,
    private val appDatabase: AppDatabase,
) {
    suspend operator fun invoke(): Result<Unit> {
        val apiResult = authRepository.logout()
        try { appDatabase.clearAllTables() } catch (e: Exception) { Timber.e(e, "clearAllTables failed") }
        dataStore.clearAll()
        return apiResult
    }
}
