package com.tradingplatform.app.widget

import android.content.Context
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
        private const val POSITION_TTL_MS = 5 * 60 * 1000L            // 5 min
        private const val PNL_TTL_MS = 5 * 60 * 1000L                 // 5 min
        private const val QUOTE_TTL_MS = 10 * 60 * 1000L              // 10 min
        private const val ALERT_RETENTION_MS = 30L * 24 * 60 * 60 * 1000L  // 30 jours

        // Symbole par défaut si aucun quote en cache (premier démarrage)
        private const val DEFAULT_QUOTE_SYMBOL = "AAPL"
    }

    override suspend fun doWork(): Result {
        // 1. Vérification VPN — si absent, garder le cache daté affiché sans retry
        if (vpnManager.state.value !is VpnState.Connected) {
            Timber.d("WidgetUpdateWorker — VPN not connected, skipping sync (cache retained)")
            return Result.success()
        }

        val portfolioId = dataStore.readString(DataStoreKeys.PORTFOLIO_ID)
        if (portfolioId == null) {
            Timber.d("WidgetUpdateWorker — portfolioId not found in DataStore, skipping sync")
            return Result.success()
        }

        var anyRetryNeeded = false

        // 2. Sync portfolio (positions + PnL) — indépendant des autres blocs
        try {
            syncPortfolio(portfolioId)
        } catch (e: IOException) {
            Timber.w(e, "WidgetUpdateWorker — portfolio sync failed (IOException), will retry")
            anyRetryNeeded = true
        } catch (e: VpnNotConnectedException) {
            // VPN coupé pendant la sync — garder le cache, pas de retry
            Timber.d("WidgetUpdateWorker — VPN disconnected during portfolio sync")
        }

        // 3. Sync quotes — indépendant du portfolio
        try {
            syncQuotes()
        } catch (e: IOException) {
            Timber.w(e, "WidgetUpdateWorker — quotes sync failed (IOException), will retry")
            anyRetryNeeded = true
        } catch (e: VpnNotConnectedException) {
            Timber.d("WidgetUpdateWorker — VPN disconnected during quotes sync")
        }

        // 4. Purge des alertes (30 jours / 500 max) — local uniquement, jamais réseau
        // Les alertes viennent de FCM → Room, pas de sync réseau ici
        try {
            purgeExpiredAlerts()
        } catch (e: Exception) {
            Timber.w(e, "WidgetUpdateWorker — alert purge failed (non-blocking)")
        }

        // 5. Rafraîchir tous les widgets Glance — ils relisent Room dans provideGlance()
        try {
            refreshAllWidgets()
        } catch (e: Exception) {
            Timber.w(e, "WidgetUpdateWorker — widget refresh failed (non-blocking)")
        }

        return if (anyRetryNeeded) Result.retry() else Result.success()
    }

    // ── Sync portfolio ─────────────────────────────────────────────────────────

    /**
     * Synchronise les positions et le PnL du portfolio.
     * Purge Room APRÈS sync réussie — jamais avant.
     *
     * Note : le Repository gère l'upsert via OnConflictStrategy.REPLACE.
     * La purge ici est redondante avec celle du Repository mais garantit
     * la cohérence quand le Worker est le seul appelant (widgets offline-first).
     *
     * @throws IOException en cas d'erreur réseau transitoire
     * @throws VpnNotConnectedException si le VPN est coupé pendant la sync
     */
    private suspend fun syncPortfolio(portfolioId: String) {
        val now = System.currentTimeMillis()

        // Sync positions (OPEN uniquement pour les widgets)
        getPositionsUseCase(portfolioId)
            .onSuccess { positions ->
                Timber.d("WidgetUpdateWorker — positions synced: ${positions.size} items")
                // Purge après upsert réussi (le Repository a déjà fait l'upsert)
                positionDao.deleteOlderThan(now - POSITION_TTL_MS)
            }
            .onFailure { e ->
                when (e) {
                    is VpnNotConnectedException -> throw e
                    is IOException -> throw e
                    else -> Timber.w(e, "WidgetUpdateWorker — positions sync error (non-retryable): ${e.message}")
                }
            }

        // Sync PnL (période journalière pour les widgets)
        getPnlUseCase(portfolioId, PnlPeriod.DAY)
            .onSuccess {
                Timber.d("WidgetUpdateWorker — PnL DAY synced")
                // Purge après upsert réussi
                pnlDao.deleteOlderThan(now - PNL_TTL_MS)
            }
            .onFailure { e ->
                when (e) {
                    is VpnNotConnectedException -> throw e
                    is IOException -> throw e
                    else -> Timber.w(e, "WidgetUpdateWorker — PnL sync error (non-retryable): ${e.message}")
                }
            }
    }

    // ── Sync quotes ────────────────────────────────────────────────────────────

    /**
     * Synchronise les quotes pour tous les symboles connus dans Room.
     * Si la table quotes est vide (premier démarrage), sync le symbole par défaut.
     * Purge Room APRÈS toutes les syncs.
     *
     * @throws IOException en cas d'erreur réseau transitoire (premier symbole en échec)
     * @throws VpnNotConnectedException si le VPN est coupé pendant la sync
     */
    private suspend fun syncQuotes() {
        val now = System.currentTimeMillis()

        // Récupérer les symboles actuellement en cache pour les rafraîchir
        val cachedSymbols = quoteDao.getAllSymbols()
        val symbolsToSync = if (cachedSymbols.isEmpty()) listOf(DEFAULT_QUOTE_SYMBOL) else cachedSymbols

        for (symbol in symbolsToSync) {
            getQuoteUseCase(symbol)
                .onSuccess {
                    Timber.d("WidgetUpdateWorker — quote synced: $symbol @ ${it.price}")
                }
                .onFailure { e ->
                    when (e) {
                        is VpnNotConnectedException -> throw e
                        is IOException -> throw e
                        else -> Timber.w(e, "WidgetUpdateWorker — quote error for $symbol (non-retryable): ${e.message}")
                    }
                }
        }

        // Purge après toutes les syncs réussies
        quoteDao.deleteOlderThan(now - QUOTE_TTL_MS)
        Timber.d("WidgetUpdateWorker — quotes purged (TTL ${QUOTE_TTL_MS / 60_000} min)")
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
        Timber.d("WidgetUpdateWorker — alerts purged (30 days / 500 max)")
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
        Timber.d("WidgetUpdateWorker — all widgets refreshed")
    }
}
