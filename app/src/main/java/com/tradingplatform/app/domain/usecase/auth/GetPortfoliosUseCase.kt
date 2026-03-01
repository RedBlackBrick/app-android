package com.tradingplatform.app.domain.usecase.auth

import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.domain.model.Portfolio
import com.tradingplatform.app.domain.repository.AuthRepository
import timber.log.Timber
import javax.inject.Inject

class GetPortfoliosUseCase @Inject constructor(
    private val authRepository: AuthRepository,
    private val dataStore: EncryptedDataStore,
) {
    suspend operator fun invoke(): Result<List<Portfolio>> {
        return authRepository.getPortfolios().onSuccess { portfolios ->
            when {
                portfolios.isEmpty() -> {
                    // État incohérent côté serveur — le caller doit forcer le logout
                }
                portfolios.size > 1 -> {
                    Timber.w("[PORTFOLIO_MULTI] count=${portfolios.size} — using portfolios[0]")
                    dataStore.writeString(DataStoreKeys.PORTFOLIO_ID, portfolios[0].id)
                }
                else -> {
                    dataStore.writeString(DataStoreKeys.PORTFOLIO_ID, portfolios[0].id)
                }
            }
        }
    }
}
