package com.tradingplatform.app.domain.usecase.auth

import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
 *
 * The three reads are independent (no data dependency between them) and each dispatches
 * to [Dispatchers.IO] internally. Running them concurrently via [async] eliminates
 * 2 sequential context switches on app startup.
 */
class GetAuthContextUseCase @Inject constructor(
    private val dataStore: EncryptedDataStore,
) {
    suspend operator fun invoke(): AuthContext = coroutineScope {
        val tokenDeferred = async { dataStore.readString(DataStoreKeys.ACCESS_TOKEN) }
        val adminDeferred = async { dataStore.readBoolean(DataStoreKeys.IS_ADMIN) }
        val setupDeferred = async { dataStore.readBoolean(DataStoreKeys.SETUP_COMPLETED) }
        val wgKeyDeferred = async { dataStore.readString(DataStoreKeys.WG_PRIVATE_KEY) }

        val setupFlag = setupDeferred.await()
        val wgPresent = wgKeyDeferred.await() != null
        val isLoggedIn = tokenDeferred.await() != null
        // Invariant : on ne peut pas être loggé sans avoir fait le setup (VPN requis pour
        // tout appel API). Si WG_PRIVATE_KEY est présente, idem — device déjà onboardé.
        // Ce fallback corrige l'état après un clearAll() legacy ou un apply() async perdu.
        val setupCompleted = setupFlag == true || wgPresent || isLoggedIn

        if (setupFlag != true && setupCompleted) {
            dataStore.writeBoolean(DataStoreKeys.SETUP_COMPLETED, true)
        }

        AuthContext(
            isLoggedIn = isLoggedIn,
            isAdmin = adminDeferred.await() ?: false,
            setupCompleted = setupCompleted,
        )
    }
}
