package com.tradingplatform.app.widget

import android.content.Context
import androidx.core.content.edit
import androidx.glance.appwidget.updateAll
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.data.local.db.CacheTtl
import com.tradingplatform.app.data.local.db.dao.AlertDao
import com.tradingplatform.app.data.local.db.dao.QuoteDao
import com.tradingplatform.app.data.local.db.dao.WatchlistDao
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.usecase.market.GetDefaultQuoteSymbolUseCase
import com.tradingplatform.app.domain.usecase.market.GetQuoteUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPnlUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPositionsUseCase
import com.tradingplatform.app.vpn.VpnNotConnectedException
import com.tradingplatform.app.vpn.SystemVpnMonitor
import com.tradingplatform.app.vpn.VpnState
import com.tradingplatform.app.vpn.WireGuardManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Worker périodique (WorkManager 15 min minimum) qui met à jour le cache Room
 * puis rafraîchit tous les widgets Glance.
 *
 * Règles impératives (CLAUDE.md §2 WorkManager) :
 * - VPN absent → Result.success() sans rien faire (garder le cache daté affiché)
 * - Purge APRÈS sync réussie — jamais avant
 * - IOException → Result.retry() uniquement si TOUTES les sections IO (positions, PnL, quotes)
 *   ont échoué (BackoffPolicy.EXPONENTIAL) — un échec isolé n'entraîne pas de retry global, le
 *   cycle périodique 15 min re-tentera de toute façon
 * - VpnNotConnectedException → Result.success() (pas de retry — cas prévisible)
 * - Alertes NON synchronisées ici — elles viennent de FCM uniquement
 * - Chaque bloc est indépendant — un échec portfolio ne bloque pas les quotes
 *
 * Politique de rétention Room (CLAUDE.md §2, valeurs dans [CacheTtl]) — purges faites par
 * les repositories après chaque sync réussie :
 * - positions : supprimer synced_at < now - CacheTtl.POSITIONS_MS (5 min)
 * - pnl_snapshots : supprimer synced_at < now - CacheTtl.PNL_RETENTION_MS (24 h — une ligne par période)
 * - quotes : supprimer synced_at < now - CacheTtl.QUOTES_MS (10 min)
 * - alerts : purge CacheTtl.ALERTS_RETENTION_MS (30 jours) / 500 max (ici — locale, données FCM)
 */
@HiltWorker
class WidgetUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val vpnManager: WireGuardManager,
    private val systemVpnMonitor: SystemVpnMonitor,
    private val dataStore: EncryptedDataStore,
    private val getPositionsUseCase: GetPositionsUseCase,
    private val getPnlUseCase: GetPnlUseCase,
    private val getQuoteUseCase: GetQuoteUseCase,
    private val getDefaultQuoteSymbolUseCase: GetDefaultQuoteSymbolUseCase,
    private val alertDao: AlertDao,
    private val quoteDao: QuoteDao,
    private val watchlistDao: WatchlistDao,
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "WidgetUpdateWorker"

        /** Plafond de symboles quotes par cycle (widgets ∪ watchlist ∪ cache). */
        internal const val QUOTES_MAX_SYMBOLS = 50

        /** Timeout global du fan-out quotes — un dépassement compte comme un échec IO. */
        internal const val QUOTES_SYNC_TIMEOUT_MS = 60_000L

        /**
         * Nombre max de requêtes quote simultanées — protège contre OOM si la watchlist
         * devient volumineuse (edge case : utilisateur suivant des centaines de symboles).
         * 5 permet un débit correct tout en bornant la pression mémoire et la charge VPS.
         */
        private const val QUOTES_CONCURRENCY = 5

        // SharedPreferences — données non sensibles (timestamp d'UI uniquement)
        const val SYNC_PREFS_NAME = "widget_sync_prefs"
        const val KEY_LAST_SYNC_ATTEMPT = "widget_last_sync_attempt"

        /**
         * Lit le timestamp de la dernière tentative de sync (réussie ou non).
         * Retourne 0L si aucune tentative n'a encore eu lieu.
         * Non sensible — stocké en SharedPreferences plain.
         */
        fun readLastSyncAttempt(context: Context): Long =
            context.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_SYNC_ATTEMPT, 0L)
    }

    override suspend fun doWork(): Result {
        // 0. Enregistrer le timestamp de cette tentative (réussie ou non) — non sensible,
        //    stocké en SharedPreferences plain pour être lu depuis les widgets sans Hilt.
        applicationContext.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putLong(KEY_LAST_SYNC_ATTEMPT, System.currentTimeMillis()) }

        // 1. Vérification VPN — si absent, garder le cache daté affiché sans retry.
        //    Accepter aussi le VPN système (app WireGuard externe) : cohérent avec
        //    VpnStatusBanner / VpnSettingsViewModel qui considèrent le tunnel up
        //    dès qu'un VPN actif est détecté par ConnectivityManager.
        val inAppConnected = vpnManager.state.value is VpnState.Connected
        val systemConnected = systemVpnMonitor.active.value
        if (!inAppConnected && !systemConnected) {
            Timber.tag(TAG).d("WidgetUpdateWorker — VPN not connected, skipping sync (cache retained)")
            return Result.success()
        }

        val portfolioId = dataStore.readString(DataStoreKeys.PORTFOLIO_ID)
        if (portfolioId == null) {
            // R5 fix — ne PAS appeler clearAllTables() si portfolioId est null.
            // Un portfolioId null indique une corruption DataStore (Keystore invalidé, reboot,
            // suppression biométrie), pas un logout intentionnel. Effacer Room dans ce cas
            // détruirait le cache offline (positions, PnL, alertes) sans possibilité de le
            // reconstruire tant que l'utilisateur ne se reconnecte pas.
            // Le cache existant reste affiché avec son timestamp "Données du HH:mm".
            Timber.tag(TAG).w(
                "WidgetUpdateWorker — portfolioId null in DataStore (possible corruption) — " +
                    "skipping sync, retaining existing Room cache"
            )
            return Result.success()
        }

        // Compteur des sections qui ont échoué sur IOException (réseau transitoire).
        // Result.retry() est retourné uniquement si TOUTES les sections IO ont échoué —
        // évite de re-sync positions+PnL si seules les quotes ont eu un hiccup (WorkManager
        // re-run complet sinon). Le cycle périodique 15min re-tentera de toute façon.
        val ioSectionsTotal = 3
        val ioFailures = AtomicBoolean(false)
        val ioFailCount = java.util.concurrent.atomic.AtomicInteger(0)

        supervisorScope {
            // 2, 2b, 3. Sync positions, PnL, and quotes in parallel
            val jobs = listOf(
                async {
                    try {
                        syncPositions(portfolioId)
                    } catch (e: IOException) {
                        Timber.tag(TAG).w(e, "WidgetUpdateWorker — positions sync failed (IOException)")
                        ioFailCount.incrementAndGet()
                    } catch (e: VpnNotConnectedException) {
                        Timber.tag(TAG).d("WidgetUpdateWorker — VPN disconnected during positions sync")
                    } catch (e: android.database.SQLException) {
                        Timber.tag(TAG).e(e, "WidgetUpdateWorker — positions sync Room error (non-retryable)")
                    }
                },
                async {
                    try {
                        syncPnl(portfolioId)
                    } catch (e: IOException) {
                        Timber.tag(TAG).w(e, "WidgetUpdateWorker — PnL sync failed (IOException)")
                        ioFailCount.incrementAndGet()
                    } catch (e: VpnNotConnectedException) {
                        Timber.tag(TAG).d("WidgetUpdateWorker — VPN disconnected during PnL sync")
                    } catch (e: android.database.SQLException) {
                        Timber.tag(TAG).e(e, "WidgetUpdateWorker — PnL sync Room error (non-retryable)")
                    }
                },
                async {
                    try {
                        val anyQuoteFailed = syncQuotes()
                        if (anyQuoteFailed) {
                            Timber.tag(TAG).w("WidgetUpdateWorker — some quotes failed (IOException)")
                            ioFailCount.incrementAndGet()
                        }
                    } catch (e: IOException) {
                        Timber.tag(TAG).w(e, "WidgetUpdateWorker — quotes sync failed entirely (IOException)")
                        ioFailCount.incrementAndGet()
                    } catch (e: VpnNotConnectedException) {
                        Timber.tag(TAG).d("WidgetUpdateWorker — VPN disconnected during quotes sync")
                    } catch (e: android.database.SQLException) {
                        Timber.tag(TAG).e(e, "WidgetUpdateWorker — quotes sync Room error (non-retryable)")
                    }
                }
            )
            jobs.awaitAll()
        }

        // Ne retry que si TOUTES les sections IO ont échoué — indique un problème réseau
        // global plutôt qu'une panne isolée.
        val shouldRetry = ioFailCount.get() == ioSectionsTotal
        ioFailures.set(shouldRetry)

        // 4. Purge des alertes (30 jours / 500 max) — local uniquement, jamais réseau
        try {
            purgeExpiredAlerts()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "WidgetUpdateWorker — alert purge failed (non-blocking)")
        }

        // 5. Rafraîchir tous les widgets Glance — ils relisent Room dans provideGlance()
        try {
            refreshAllWidgets()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "WidgetUpdateWorker — widget refresh failed (non-blocking)")
        }

        return if (ioFailures.get()) Result.retry() else Result.success()
    }

    // ── Sync positions ──────────────────────────────────────────────────────────

    /**
     * Synchronise les positions du portfolio.
     * L'upsert + purge sont atomiques dans le Repository (via [PositionDao.upsertAllAndPurge]).
     * Le Worker n'a plus besoin de purger séparément.
     *
     * @throws IOException en cas d'erreur réseau transitoire
     * @throws VpnNotConnectedException si le VPN est coupé pendant la sync
     */
    private suspend fun syncPositions(portfolioId: String) {
        getPositionsUseCase(portfolioId)
            .onSuccess { positions ->
                Timber.tag(TAG).d("WidgetUpdateWorker — positions synced: ${positions.size} items")
            }
            .onFailure { e ->
                when (e) {
                    is VpnNotConnectedException -> throw e
                    is IOException -> throw e
                    else -> Timber.tag(TAG).w(e, "WidgetUpdateWorker — positions sync error (non-retryable): ${e.message}")
                }
            }
    }

    // ── Sync PnL ────────────────────────────────────────────────────────────────

    /**
     * Synchronise le PnL du portfolio : DAY + chaque période configurée par une instance de
     * [PnlWidget] ([PnlWidget.configuredPeriods]).
     * La persistance est faite par `PortfolioRepositoryImpl.getPnlSummary` (unique writer de
     * `pnl_snapshots`, upsert + purge atomiques via `PnlDao.upsertAndPurge`) — le Worker
     * n'écrit pas Room lui-même.
     *
     * Une IOException sur une période n'empêche pas les autres ; elle est relancée à la fin.
     *
     * @throws IOException en cas d'erreur réseau transitoire (sur au moins une période)
     * @throws VpnNotConnectedException si le VPN est coupé pendant la sync (immédiat)
     */
    private suspend fun syncPnl(portfolioId: String) {
        val periods = buildSet {
            add(PnlPeriod.DAY)
            PnlWidget.configuredPeriods(applicationContext)
                .mapNotNullTo(this) { PnlPeriod.fromApiString(it) }
        }

        var ioFailure: IOException? = null
        for (period in periods) {
            val failure = getPnlUseCase(portfolioId, period).exceptionOrNull()
            if (failure == null) {
                Timber.tag(TAG).d("WidgetUpdateWorker — PnL $period synced")
                continue
            }
            when (failure) {
                is VpnNotConnectedException -> throw failure
                is IOException -> ioFailure = failure
                else -> Timber.tag(TAG).w(failure, "WidgetUpdateWorker — PnL $period sync error (non-retryable): ${failure.message}")
            }
        }
        ioFailure?.let { throw it }
    }

    // ── Sync quotes ────────────────────────────────────────────────────────────

    /**
     * Symboles à rafraîchir : tickers configurés par un [QuoteWidget] (affichés sur l'écran
     * d'accueil, prioritaires) ∪ watchlist ∪ symboles déjà en cache `quotes`, en majuscules,
     * dédupliqués, plafonnés à [QUOTES_MAX_SYMBOLS]. Si l'union est vide (premier démarrage),
     * le symbole par défaut ([GetDefaultQuoteSymbolUseCase]).
     */
    private suspend fun resolveQuoteSymbols(): List<String> {
        val symbols = LinkedHashSet<String>()
        fun addSymbols(source: Collection<String>) {
            source.mapNotNullTo(symbols) { raw -> raw.trim().uppercase().takeIf { it.isNotEmpty() } }
        }
        addSymbols(QuoteWidget.configuredSymbols(applicationContext))
        addSymbols(watchlistDao.getAllSymbols())
        addSymbols(quoteDao.getAllSymbols())

        if (symbols.isEmpty()) return listOf(getDefaultQuoteSymbolUseCase())
        if (symbols.size > QUOTES_MAX_SYMBOLS) {
            Timber.tag(TAG).w(
                "WidgetUpdateWorker — ${symbols.size} quote symbols, capped to $QUOTES_MAX_SYMBOLS"
            )
        }
        return symbols.take(QUOTES_MAX_SYMBOLS)
    }

    /**
     * Synchronise les quotes des symboles de [resolveQuoteSymbols].
     * L'upsert + purge sont atomiques dans le Repository (via [QuoteDao.upsertAndPurge]).
     *
     * Un échec sur un symbole isolé ne bloque pas les autres — la boucle continue.
     * Le fan-out complet est borné par [QUOTES_SYNC_TIMEOUT_MS] : un dépassement compte
     * comme un échec IO (retourne true), les quotes déjà écrites restent en cache.
     *
     * @throws VpnNotConnectedException si le VPN est coupé pendant la sync (remonte toujours)
     * @return true si au moins un symbole a échoué (IOException) ou si le timeout global a expiré
     */
    private suspend fun syncQuotes(): Boolean {
        val symbolsToSync = resolveQuoteSymbols()
        val anySymbolFailed = AtomicBoolean(false)
        try {
            withTimeout(QUOTES_SYNC_TIMEOUT_MS) {
                supervisorScope {
                    // Borner la concurrence — empêche l'OOM et limite la charge VPS.
                    // Chaque permit représente une requête en vol.
                    val semaphore = Semaphore(QUOTES_CONCURRENCY)
                    val jobs = symbolsToSync.map { symbol ->
                        async {
                            semaphore.withPermit {
                                getQuoteUseCase(symbol)
                                    .onSuccess {
                                        Timber.tag(TAG).d("WidgetUpdateWorker — quote synced: $symbol @ ${it.price}")
                                    }
                                    .onFailure { e ->
                                        when (e) {
                                            is VpnNotConnectedException -> throw e
                                            is IOException -> {
                                                Timber.tag(TAG).w(e, "WidgetUpdateWorker — quote sync failed for $symbol, continuing with others")
                                                anySymbolFailed.set(true)
                                            }
                                            else -> Timber.tag(TAG).w(e, "WidgetUpdateWorker — quote error for $symbol (non-retryable): ${e.message}")
                                        }
                                    }
                            }
                        }
                    }
                    jobs.awaitAll()
                }
            }
        } catch (e: TimeoutCancellationException) {
            // Timeout propre à ce withTimeout (une annulation externe lève une
            // CancellationException simple, non interceptée ici) → échec IO transitoire.
            Timber.tag(TAG).w("WidgetUpdateWorker — quotes fan-out timed out after ${QUOTES_SYNC_TIMEOUT_MS}ms")
            return true
        }
        return anySymbolFailed.get()
    }

    // ── Purge alertes ──────────────────────────────────────────────────────────

    /**
     * Purge les alertes expirées (30 jours) et au-delà des 500 dernières.
     * Les deux DELETE sont atomiques via [AlertDao.purgeExpired] (@Transaction).
     * Les alertes viennent de FCM → Room uniquement, pas de sync réseau ici.
     */
    private suspend fun purgeExpiredAlerts() {
        val cutoff = System.currentTimeMillis() - CacheTtl.ALERTS_RETENTION_MS
        alertDao.purgeExpired(cutoff)
        Timber.tag(TAG).d("WidgetUpdateWorker — alerts purged (30 days / 500 max)")
    }

    // ── Rafraîchissement widgets ───────────────────────────────────────────────

    /**
     * Déclenche la mise à jour de tous les widgets Glance.
     * Chaque widget re-lira ses données depuis Room dans son provideGlance().
     * Glance gère les instances multiples via GlanceId.
     */
    private suspend fun refreshAllWidgets() {
        PnlWidget().updateAll(applicationContext)
        PositionsWidget().updateAll(applicationContext)
        AlertsWidget().updateAll(applicationContext)
        SystemStatusWidget().updateAll(applicationContext)
        QuoteWidget().updateAll(applicationContext)
        Timber.tag(TAG).d("WidgetUpdateWorker — all widgets refreshed")
    }
}
