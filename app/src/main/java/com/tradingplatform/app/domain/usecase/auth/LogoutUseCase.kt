package com.tradingplatform.app.domain.usecase.auth

import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.data.local.db.AppDatabase
import com.tradingplatform.app.domain.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

class LogoutUseCase @Inject constructor(
    private val authRepository: AuthRepository,
    private val dataStore: EncryptedDataStore,
    private val appDatabase: AppDatabase,
) {
    suspend operator fun invoke(): Result<Unit> {
        val apiResult = authRepository.logout()
        // Room.clearAllTables() est bloquant — doit tourner sur IO pour ne pas planter
        // si l'appelant est sur le thread principal (ex: viewModelScope.launch).
        withContext(Dispatchers.IO) {
            try { appDatabase.clearAllTables() } catch (e: Exception) { Timber.e(e, "clearAllTables failed") }
        }
        dataStore.clearSession()
        return apiResult
    }
}
