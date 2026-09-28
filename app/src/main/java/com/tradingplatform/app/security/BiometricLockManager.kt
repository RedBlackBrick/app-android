package com.tradingplatform.app.security

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.data.session.TokenHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Propriétaire unique du verrou biométrique d'inactivité (CLAUDE.md §4).
 *
 * Possède le timestamp de dernière interaction, le job de polling et [isLocked].
 * - `MainActivity.dispatchTouchEvent` ne fait que [onUserInteraction] : la recréation de
 *   l'Activity (rotation, changement de config) ne remet plus l'horloge à zéro.
 * - Observe [androidx.lifecycle.ProcessLifecycleOwner] (enregistré par `TradingApplication`) :
 *   `onStart` verrouille si le délai est dépassé puis relance le polling ; `onStop` coupe le
 *   polling et persiste l'état.
 * - [isLocked] démarre à `true` (**fail-closed**) jusqu'à [restorePersistedState] (session
 *   existante) ou [unlock] (pas de session : Setup/Login ne sont pas protégés).
 *
 * L'état (verrou + dernier timestamp) est persisté en `commit()` dans [EncryptedDataStore] pour
 * survivre à un process kill : un redémarrage après > 5 min d'inactivité, ou après un kill
 * pendant le verrou, redémarre verrouillé.
 */
@Singleton
class BiometricLockManager internal constructor(
    private val dataStore: EncryptedDataStore,
    private val applicationScope: CoroutineScope,
    private val clock: () -> Long,
    /** Session authentifiée en cours ? Sans session (Setup/Login), le timeout ne verrouille pas. */
    private val hasSession: () -> Boolean = { true },
) : DefaultLifecycleObserver {

    @Inject
    constructor(
        dataStore: EncryptedDataStore,
        applicationScope: CoroutineScope,
        tokenHolder: TokenHolder,
    ) : this(
        dataStore = dataStore,
        applicationScope = applicationScope,
        clock = System::currentTimeMillis,
        hasSession = { tokenHolder.accessToken != null },
    )

    companion object {
        const val INACTIVITY_TIMEOUT_MS = 5 * 60 * 1000L  // 5 min
        const val INACTIVITY_POLL_MS = 5_000L             // 5 s
    }

    /**
     * Timestamp de la dernière interaction tactile. Mis à jour sans allocation par
     * [onUserInteraction] (appelé à chaque touch event), lu périodiquement par le polling.
     */
    private val lastInteractionAt = AtomicLong(0L)

    private val _isLocked = MutableStateFlow(true)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    /** true dès qu'un lock()/unlock() explicite a eu lieu — un restore tardif ne l'écrase pas. */
    @Volatile
    private var explicitTransition = false

    @Volatile
    private var pollJob: Job? = null

    // ── API publique ───────────────────────────────────────────────────────────

    /** Appelé à chaque interaction utilisateur. Ignoré pendant le verrou. */
    fun onUserInteraction() {
        if (!_isLocked.value) lastInteractionAt.set(clock())
    }

    fun lock() {
        explicitTransition = true
        if (!_isLocked.value) Timber.d("BiometricLockManager: locking")
        _isLocked.value = true
        persist(locked = true, lastInteraction = lastInteractionAt.get())
    }

    /** Déverrouille et ré-arme l'horloge d'inactivité (nouvelle fenêtre de 5 min). */
    fun unlock() {
        explicitTransition = true
        val now = clock()
        lastInteractionAt.set(now)
        _isLocked.value = false
        persist(locked = false, lastInteraction = now)
    }

    /**
     * Restaure l'état persisté. Appelé par `TradingApplication` dans la même coroutine que la
     * lecture de l'access token, uniquement quand une session existe.
     *
     * `locked = persisté ?: true` ; `last = persisté ?: 0` ; verrouillé si `locked` OU si la
     * dernière interaction date de plus de [INACTIVITY_TIMEOUT_MS].
     */
    suspend fun restorePersistedState() {
        val persistedLocked = try {
            dataStore.readBoolean(DataStoreKeys.BIOMETRIC_LOCKED)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "BiometricLockManager: failed to read persisted lock — defaulting to locked")
            null
        }
        val persistedLast = try {
            dataStore.readLong(DataStoreKeys.LAST_INTERACTION_AT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "BiometricLockManager: failed to read last interaction — defaulting to expired")
            null
        }
        val locked = persistedLocked ?: true
        val last = persistedLast ?: 0L

        if (explicitTransition) {
            Timber.d("BiometricLockManager: restore skipped — explicit transition already happened")
            return
        }
        lastInteractionAt.set(last)
        _isLocked.value = locked || isExpired(last)
        Timber.d("BiometricLockManager: restored state locked=${_isLocked.value}")
    }

    // ── Lifecycle (ProcessLifecycleOwner) ─────────────────────────────────────

    override fun onStart(owner: LifecycleOwner) {
        checkExpiry()
        startPolling()
    }

    override fun onStop(owner: LifecycleOwner) {
        pollJob?.cancel()
        pollJob = null
        persist(locked = _isLocked.value, lastInteraction = lastInteractionAt.get())
    }

    // ── Interne ────────────────────────────────────────────────────────────────

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = applicationScope.launch {
            while (isActive) {
                delay(INACTIVITY_POLL_MS)
                checkExpiry()
            }
        }
    }

    private fun checkExpiry() {
        if (!_isLocked.value && hasSession() && isExpired(lastInteractionAt.get())) {
            Timber.d("BiometricLockManager: inactivity timeout")
            lock()
        }
    }

    private fun isExpired(last: Long): Boolean = clock() - last >= INACTIVITY_TIMEOUT_MS

    private fun persist(locked: Boolean, lastInteraction: Long) {
        applicationScope.launch {
            try {
                // Les deux clés sont dans EncryptedDataStore.criticalKeys → commit() synchrone.
                dataStore.writeBoolean(DataStoreKeys.BIOMETRIC_LOCKED, locked)
                dataStore.writeLong(DataStoreKeys.LAST_INTERACTION_AT, lastInteraction)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "BiometricLockManager: failed to persist lock state=$locked")
            }
        }
    }
}
