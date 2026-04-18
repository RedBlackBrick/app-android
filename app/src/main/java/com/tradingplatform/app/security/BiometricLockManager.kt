package com.tradingplatform.app.security

import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gère l'état du verrou biométrique d'inactivité (CLAUDE.md §4).
 * Singleton partagé entre MainActivity (qui lock) et AppNavViewModel (qui expose l'état à l'UI).
 *
 * L'état est persisté dans [EncryptedDataStore] pour survivre à un process kill :
 * si l'app est tuée par le système pendant qu'elle est verrouillée, au redémarrage
 * le verrou est restauré avant que l'écran ne soit visible — empêche l'exposition
 * des données de trading sans authentification.
 */
@Singleton
class BiometricLockManager @Inject constructor(
    private val dataStore: EncryptedDataStore,
    private val applicationScope: CoroutineScope,
) {
    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    /**
     * Restaure l'état persisté depuis EncryptedDataStore.
     * Appelé par [TradingApplication.onCreate] au démarrage pour que le verrou
     * soit effectif avant la première composition UI.
     */
    suspend fun restorePersistedState() {
        try {
            val locked = dataStore.readBoolean(DataStoreKeys.BIOMETRIC_LOCKED) ?: false
            if (locked) {
                _isLocked.value = true
                Timber.d("BiometricLockManager: restored locked state from persistent storage")
            }
        } catch (e: Exception) {
            Timber.w(e, "BiometricLockManager: failed to restore persisted state — defaulting to unlocked")
        }
    }

    fun lock() {
        _isLocked.value = true
        persistAsync(true)
    }

    fun unlock() {
        _isLocked.value = false
        persistAsync(false)
    }

    private fun persistAsync(locked: Boolean) {
        applicationScope.launch(Dispatchers.IO) {
            try {
                dataStore.writeBoolean(DataStoreKeys.BIOMETRIC_LOCKED, locked)
            } catch (e: Exception) {
                Timber.w(e, "BiometricLockManager: failed to persist lock state=$locked")
            }
        }
    }
}
