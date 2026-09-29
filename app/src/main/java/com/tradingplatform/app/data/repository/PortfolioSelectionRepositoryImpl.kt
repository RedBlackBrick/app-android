package com.tradingplatform.app.data.repository

import com.tradingplatform.app.data.api.AuthApi
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.data.local.db.dao.PnlDao
import com.tradingplatform.app.data.local.db.dao.PositionDao
import com.tradingplatform.app.data.model.toDomain
import com.tradingplatform.app.data.session.SessionManager
import com.tradingplatform.app.domain.model.Portfolio
import com.tradingplatform.app.domain.repository.PortfolioSelectionRepository
import com.tradingplatform.app.domain.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Portefeuille actif d'un compte multi-portefeuille (voir [PortfolioSelectionRepository]).
 *
 * ## Persistance
 * La sélection est écrite dans `DataStoreKeys.PORTFOLIO_ID` : c'est la clé que relisent les
 * widgets et le `WidgetUpdateWorker` (« portefeuille actif »). Elle est effacée avec le reste de
 * la session par `EncryptedDataStore.clearSession()` (logout) et `resetCorruptedStore()`
 * (corruption Keystore) ; l'état mémoire est remis à zéro ici sur `forcedLogoutEvents`
 * (tous les chemins de logout) et `keystoreCorruptionEvents`.
 *
 * ## Caches portfolio-scopés
 * Room v7 n'a pas de colonne `portfolio_id` : quand l'id actif change (y compris `null` → id),
 * `positions` et `pnl_snapshots` sont purgés **avant** d'émettre le nouvel id, pour qu'aucun
 * collecteur ne voie brièvement les lignes de l'ancien portefeuille sous le nouveau. Si la purge
 * échoue, le changement est abandonné (l'id actif reste l'ancien) : mieux vaut une erreur qu'un
 * affichage croisé entre deux portefeuilles. Alertes, cours, watchlist et devices sont intacts.
 *
 * ## Concurrence
 * [refresh] et [select] sont sérialisés par un [Mutex]. Un compteur d'époque de session,
 * incrémenté à la fin de session, rend périmé un [refresh] en vol : sa réponse n'est ni publiée
 * ni persistée (elle ré-écrirait sinon `PORTFOLIO_ID` juste après le logout).
 */
@Singleton
class PortfolioSelectionRepositoryImpl @Inject constructor(
    private val authApi: AuthApi,
    private val dataStore: EncryptedDataStore,
    private val positionDao: PositionDao,
    private val pnlDao: PnlDao,
    sessionManager: SessionManager,
    applicationScope: CoroutineScope,
) : PortfolioSelectionRepository {

    private val _portfolios = MutableStateFlow<List<Portfolio>>(emptyList())
    override val portfolios: StateFlow<List<Portfolio>> = _portfolios.asStateFlow()

    private val _activePortfolioId = MutableStateFlow<String?>(null)
    override val activePortfolioId: StateFlow<String?> = _activePortfolioId.asStateFlow()

    private val mutex = Mutex()

    /** Incrémenté à chaque fin de session (logout, corruption Keystore). */
    private val sessionEpoch = AtomicInteger(0)

    init {
        // UNDISPATCHED : l'abonnement est effectif dès la fin du constructeur (SharedFlow replay=0,
        // un événement émis avant l'abonnement serait perdu) — même pattern que PrivateWsClient.
        applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessionManager.forcedLogoutEvents.collect { onSessionEnded() }
        }
        applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessionManager.keystoreCorruptionEvents.collect { onSessionEnded() }
        }
        // Lecture disque hors du constructeur (le singleton peut être créé sur le thread principal).
        applicationScope.launch { restorePersistedSelection() }
    }

    override suspend fun refresh(): Result<List<Portfolio>> = runCatchingCancellable {
        mutex.withLock {
            val epoch = sessionEpoch.get()
            val response = authApi.getPortfolios()
            if (!response.isSuccessful) {
                error("Get portfolios failed: HTTP ${response.code()}")
            }
            val list = response.body()?.map { it.toDomain() } ?: emptyList()

            // Session terminée pendant la requête : ne rien publier ni persister.
            check(sessionEpoch.get() == epoch) { "Session ended during portfolio refresh" }

            if (list.isEmpty()) {
                Timber.tag(TAG).e("PortfolioSelection: empty portfolio list — incoherent server state")
                error("No portfolio found")
            }
            if (list.size > 1) {
                Timber.tag(TAG).w("PortfolioSelection: [PORTFOLIO_MULTI] count=${list.size}")
            }

            // Sélection courante (mémoire, sinon disque si la restauration asynchrone n'a pas encore
            // abouti) conservée tant qu'elle existe dans la liste ; sinon, premier portefeuille.
            val previous = currentActiveId()
            val target = previous?.takeIf { id -> list.any { it.id == id } } ?: list.first().id

            // Liste publiée avant l'id actif : un collecteur de l'id actif retrouve son portefeuille.
            _portfolios.value = list
            activate(target = target, previous = previous)
            list
        }
    }

    override suspend fun select(portfolioId: String): Result<Unit> = runCatchingCancellable {
        mutex.withLock { selectLocked(portfolioId) }
    }

    /** Appelé sous [mutex]. */
    private suspend fun selectLocked(portfolioId: String) {
        check(_portfolios.value.any { it.id == portfolioId }) { "Unknown portfolio" }
        val previous = currentActiveId()
        if (previous == portfolioId) {
            // Déjà actif : no-op (ni purge ni écriture). Seul cas résiduel : la restauration
            // asynchrone depuis le disque n'a pas encore publié l'id.
            _activePortfolioId.compareAndSet(null, portfolioId)
        } else {
            activate(target = portfolioId, previous = previous)
        }
    }

    /**
     * Applique [target] comme portefeuille actif. Appelé sous [mutex].
     * Ordre : purge (si l'id change) → persistance → émission.
     */
    private suspend fun activate(target: String, previous: String?) {
        if (previous != target) {
            positionDao.deleteAll()
            pnlDao.deleteAll()
            Timber.tag(TAG).d("PortfolioSelection: active portfolio changed — positions and pnl caches purged")
        }
        dataStore.writeString(DataStoreKeys.PORTFOLIO_ID, target)
        _activePortfolioId.value = target
    }

    private suspend fun currentActiveId(): String? = _activePortfolioId.value ?: readPersistedId()

    private suspend fun readPersistedId(): String? =
        dataStore.readString(DataStoreKeys.PORTFOLIO_ID)?.takeIf { it.isNotBlank() }

    /**
     * Initialise [activePortfolioId] depuis le disque. `compareAndSet(null, …)` : n'écrase jamais
     * une sélection faite entre-temps ; l'époque protège d'un logout survenu pendant la lecture.
     */
    private suspend fun restorePersistedSelection() {
        val epoch = sessionEpoch.get()
        runCatchingCancellable { readPersistedId() }
            .onSuccess { persisted ->
                if (persisted != null && sessionEpoch.get() == epoch) {
                    _activePortfolioId.compareAndSet(null, persisted)
                }
            }
            .onFailure { Timber.tag(TAG).w(it, "PortfolioSelection: could not restore the persisted selection") }
    }

    /**
     * Fin de session : remet l'état mémoire à zéro. La clé persistée `PORTFOLIO_ID` est déjà
     * effacée par `clearSession()` / `resetCorruptedStore()` selon le chemin de sortie.
     */
    private fun onSessionEnded() {
        sessionEpoch.incrementAndGet()
        _portfolios.value = emptyList()
        _activePortfolioId.value = null
        Timber.tag(TAG).d("PortfolioSelection: session ended — in-memory selection cleared")
    }

    private companion object {
        const val TAG = "PortfolioSelectionRepo"
    }
}
