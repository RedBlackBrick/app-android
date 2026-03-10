package com.tradingplatform.app.domain.usecase.auth

import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import javax.inject.Inject

/**
 * Auth context needed by navigation and transversal ViewModels.
 *
 * [setupCompleted] is false on first launch — the app shows [SetupScreen] to guide
 * the user through the WireGuard onboarding QR scan before reaching [LoginScreen].
 */
data class AuthContext(
    val isLoggedIn: Boolean,
    val isAdmin: Boolean,
    val setupCompleted: Boolean,
)

/**
 * Encapsulates reading auth state from [EncryptedDataStore].
 *
 * Used by [AppNavViewModel] to determine start destination and admin tab visibility,
 * without coupling the navigation layer directly to the data layer.
 */
class GetAuthContextUseCase @Inject constructor(
    private val dataStore: EncryptedDataStore,
) {
    suspend operator fun invoke(): AuthContext {
        val token = dataStore.readString(DataStoreKeys.ACCESS_TOKEN)
        val admin = dataStore.readBoolean(DataStoreKeys.IS_ADMIN) ?: false
        val setupCompleted = dataStore.readBoolean(DataStoreKeys.SETUP_COMPLETED) ?: false
        return AuthContext(
            isLoggedIn = token != null,
            isAdmin = admin,
            setupCompleted = setupCompleted,
        )
    }
}
