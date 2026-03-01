package com.tradingplatform.app.domain.usecase.auth

import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.domain.model.AuthTokens
import com.tradingplatform.app.domain.model.User
import com.tradingplatform.app.domain.repository.AuthRepository
import javax.inject.Inject

class LoginUseCase @Inject constructor(
    private val authRepository: AuthRepository,
    private val dataStore: EncryptedDataStore,
) {
    suspend operator fun invoke(email: String, password: String): Result<Pair<User, AuthTokens>> {
        return authRepository.login(email, password).onSuccess { (user, tokens) ->
            dataStore.writeString(DataStoreKeys.ACCESS_TOKEN, tokens.accessToken)
            dataStore.writeLong(DataStoreKeys.USER_ID, user.id)
            dataStore.writeBoolean(DataStoreKeys.IS_ADMIN, user.isAdmin)
        }
    }
}
