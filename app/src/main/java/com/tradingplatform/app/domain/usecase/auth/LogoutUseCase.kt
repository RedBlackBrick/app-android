package com.tradingplatform.app.domain.usecase.auth

import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.domain.repository.AuthRepository
import javax.inject.Inject

class LogoutUseCase @Inject constructor(
    private val authRepository: AuthRepository,
    private val dataStore: EncryptedDataStore,
) {
    suspend operator fun invoke(): Result<Unit> {
        val apiResult = authRepository.logout()
        dataStore.clearAll()
        return apiResult
    }
}
