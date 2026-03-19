package com.tradingplatform.app.widget

import android.content.Context
import androidx.core.content.edit
import androidx.glance.appwidget.updateAll
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tradingplatform.app.data.local.datastore.DataStoreKeys
import com.tradingplatform.app.data.local.datastore.EncryptedDataStore
import com.tradingplatform.app.data.local.db.dao.AlertDao
import com.tradingplatform.app.data.local.db.dao.PnlDao
import com.tradingplatform.app.data.local.db.dao.PositionDao
import com.tradingplatform.app.data.local.db.dao.QuoteDao
import com.tradingplatform.app.domain.model.AppDefaults
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.usecase.market.GetQuoteUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPnlUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPositionsUseCase
import com.tradingplatform.app.vpn.VpnNotConnectedException
import com.tradingplatform.app.vpn.VpnState
import com.tradingplatform.app.vpn.WireGuardManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.io.IOException

/**
 * Worker périodique (WorkManager 15 min minimum) qui met à jour le cache Room
 * puis rafraîchit tous les widgets Glance.
 *
 * Règles impératives (CLAUDE.md §2 WorkManager) :
 * - VPN absent → Result.success() sans rien faire (garder le cache daté affiché)
 * - Purge APRÈS sync réussie — jamais avant
 * - IOException → Result.retry() si au moins un bloc a échoué (BackoffPolicy.EXPONENTIAL)
 * - VpnNotConnectedException → Result.success() (pas de retry — cas prévisible)
 * - Alertes NON synchronisées ici — elles viennent de FCM uniquement
 * - Chaque bloc est indépendant — un échec portfolio ne bloque pas les quotes
 *
 * Politique de rétention Room (CLAUDE.md §2) :
 * - positions : supprimer synced_at < now - 5 min
 * - pnl_snapshots : supprimer synced_at < now - 5 min
 * - quotes : supprimer synced_at < now - 10 min
 * - alerts : purge 30 jours / 500 max (locale uniquement — données FCM)
 */
@HiltWorker
class WidgetUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val vpnManager: WireGuardManager,
    private val dataStore: EncryptedDataStore,
    private val getPositionsUseCase: GetPositionsUseCase,
    private val getPnlUseCase: GetPnlUseCase,
    private val getQuoteUseCase: GetQuoteUseCase,
    private val positionDao: PositionDao,
    private val pnlDao: PnlDao,
    private val alertDao: AlertDao,
    private val quoteDao: QuoteDao,
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "WidgetUpdateWorker"
        private const val POSITION_TTL_MS = 5 * 60 * 1000L            // 5 min
        private const val PNL_TTL_MS = 5 * 60 * 1000L                 // 5 min
        private const val QUOTE_TTL_MS = 10 * 60 * 1000L              // 10 min
        private const val ALERT_RETENTION_MS = 30L * 24 * 60 * 60 * 1000L  // 30 jours

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

        // 1. Vérification VPN — si absent, garder le cache daté affiché sans retry
        if (vpnManager.state.value !is VpnState.Connected) {
            Timber.tag(TAG).d("WidgetUpdateWorker — VPN not connected, skipping sync (cache retained)")
            return Result.success()
        }

        val portfolioId = dataStore.readString(DataStoreKeys.PORTFOLIO_ID)
        if (portfolioId == null) {
            Timber.tag(TAG).d("WidgetUpdateWorker — portfolioId not found in DataStore, skipping sync")
            return Result.success()
        }

        var anyRetryNeeded = false

        // 2. Sync positions — indépendant du PnL et des autres blocs
        try {
            syncPositions(portfolioId)
        } catch (e: IOException) {
            Timber.tag(TAG).w(e, "WidgetUpdateWorker — positions sync failed (IOException), will retry")
            anyRetryNeeded = true
        } catch (e: VpnNotConnectedException) {
            Timber.tag(TAG).d("WidgetUpdateWorker — VPN disconnected during positions sync")
        }

        // 2b. Sync PnL — indépendant des positions et des autres blocs
        try {
            syncPnl(portfolioId)
        } catch (e: IOException) {
            Timber.tag(TAG).w(e, "WidgetUpdateWorker — PnL sync failed (IOException), will retry")
            anyRetryNeeded = true
        } catch (e: VpnNotConnectedException) {
            Timber.tag(TAG).d("WidgetUpdateWorker — VPN disconnected during PnL sync")
        }

        // 3. Sync quotes — indépendant du portfolio
        try {
            val anyQuoteFailed = syncQuotes()
            if (anyQuoteFailed) {
                Timber.tag(TAG).w("WidgetUpdateWorker — some quotes failed (IOException), will retry")
                anyRetryNeeded = true
            }
        } catch (e: IOException) {
            Timber.tag(TAG).w(e, "WidgetUpdateWorker — quotes sync failed entirely (IOException), will retry")
            anyRetryNeeded = true
        } catch (e: VpnNotConnectedException) {
            Timber.tag(TAG).d("WidgetUpdateWorker — VPN disconnected during quotes sync")
        }

        // 4. Purge des alertes (30 jours / 500 max) — local uniquement, jamais réseau
        // Les alertes viennent de FCM → Room, pas de sync réseau ici
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

        return if (anyRetryNeeded) Result.retry() else Result.success()
    }

    // ── Sync positions ──────────────────────────────────────────────────────────

    /**
     * Synchronise les positions du portfolio.
     * Purge Room APRÈS sync réussie — jamais avant.
     *
     * Note : le Repository gère l'upsert via OnConflictStrategy.REPLACE.
     * La purge ici est redondante avec celle du Repository mais garantit
     * la cohérence quand le Worker est le seul appelant (widgets offline-first).
     *
     * @throws IOException en cas d'erreur réseau transitoire
     * @throws VpnNotConnectedException si le VPN est coupé pendant la sync
     */
    private suspend fun syncPositions(portfolioId: String) {
        val now = System.currentTimeMillis()

        getPositionsUseCase(portfolioId)
            .onSuccess { positions ->
                Timber.tag(TAG).d("WidgetUpdateWorker — positions synced: ${positions.size} items")
                // Purge après upsert réussi (le Repository a déjà fait l'upsert)
                positionDao.deleteOlderThan(now - POSITION_TTL_MS)
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
     * Synchronise le PnL du portfolio (période journalière pour les widgets).
     * Purge Room APRÈS sync réussie — jamais avant.
     *
     * @throws IOException en cas d'erreur réseau transitoire
     * @throws VpnNotConnectedException si le VPN est coupé pendant la sync
     */
    private suspend fun syncPnl(portfolioId: String) {
        val now = System.currentTimeMillis()

        getPnlUseCase(portfolioId, PnlPeriod.DAY)
            .onSuccess {
                Timber.tag(TAG).d("WidgetUpdateWorker — PnL DAY synced")
                // Purge après upsert réussi
                pnlDao.deleteOlderThan(now - PNL_TTL_MS)
            }
            .onFailure { e ->
                when (e) {
                    is VpnNotConnectedException -> throw e
                    is IOException -> throw e
                    else -> Timber.tag(TAG).w(e, "WidgetUpdateWorker — PnL sync error (non-retryable): ${e.message}")
                }
            }
    }

    // ── Sync quotes ────────────────────────────────────────────────────────────

    /**
     * Synchronise les quotes pour tous les symboles connus dans Room.
     * Si la table quotes est vide (premier démarrage), sync le symbole par défaut.
     * Purge Room APRÈS toutes les syncs.
     *
     * Un échec sur un symbole isolé ne bloque pas les autres — la boucle continue.
     * Retourne true si au moins un symbole a échoué avec une IOException (pour que
     * doWork() puisse positionner anyRetryNeeded).
     *
     * @throws VpnNotConnectedException si le VPN est coupé pendant la sync (remonte toujours)
     * @return true si au moins un symbole a échoué, false si tous ont réussi
     */
    private suspend fun syncQuotes(): Boolean {
        val now = System.currentTimeMillis()

        // Récupérer les symboles actuellement en cache pour les rafraîchir
        val cachedSymbols = quoteDao.getAllSymbols()
        val symbolsToSync = if (cachedSymbols.isEmpty()) listOf(AppDefaults.DEFAULT_QUOTE_SYMBOL) else cachedSymbols

        var anySymbolFailed = false
        for (symbol in symbolsToSync) {
            getQuoteUseCase(symbol)
                .onSuccess {
                    Timber.tag(TAG).d("WidgetUpdateWorker — quote synced: $symbol @ ${it.price}")
                }
                .onFailure { e ->
                    when (e) {
                        is VpnNotConnectedException -> throw e
                        is IOException -> {
                            Timber.tag(TAG).w(e, "WidgetUpdateWorker — quote sync failed for $symbol, continuing with others")
                            anySymbolFailed = true
                        }
                        else -> Timber.tag(TAG).w(e, "WidgetUpdateWorker — quote error for $symbol (non-retryable): ${e.message}")
                    }
                }
        }

        // Purge après toutes les syncs (même partielle — au moins un symbole réussi suffit)
        quoteDao.deleteOlderThan(now - QUOTE_TTL_MS)
        Timber.tag(TAG).d("WidgetUpdateWorker — quotes purged (TTL ${QUOTE_TTL_MS / 60_000} min)")

        return anySymbolFailed
    }

    // ── Purge alertes ──────────────────────────────────────────────────────────

    /**
     * Purge les alertes expirées (30 jours) et au-delà des 500 dernières.
     * Les alertes viennent de FCM → Room uniquement, pas de sync réseau ici.
     */
    private suspend fun purgeExpiredAlerts() {
        val cutoff = System.currentTimeMillis() - ALERT_RETENTION_MS
        alertDao.deleteOlderThan(cutoff)
        alertDao.keepOnlyLatest500()
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
