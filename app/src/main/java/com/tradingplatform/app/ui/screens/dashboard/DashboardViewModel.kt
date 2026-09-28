package com.tradingplatform.app.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.model.NavSummary
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.PnlSummary
import com.tradingplatform.app.domain.model.PortfolioCircuitBreakerStatus
import com.tradingplatform.app.domain.model.Quote
import com.tradingplatform.app.domain.model.WsConnectionState
import com.tradingplatform.app.domain.model.ActivityItem
import com.tradingplatform.app.domain.usecase.activity.GetActivityFeedUseCase
import com.tradingplatform.app.domain.usecase.auth.GetPortfolioIdUseCase
import com.tradingplatform.app.domain.usecase.market.GetDefaultQuoteSymbolUseCase
import com.tradingplatform.app.domain.usecase.market.GetPublicWsConnectionStateUseCase
import com.tradingplatform.app.domain.usecase.market.GetQuoteStreamUseCase
import com.tradingplatform.app.domain.usecase.market.GetQuoteUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetActiveStrategyCountUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPnlUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPortfolioNavUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPortfolioWsUpdatesUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetWsConnectionStateUseCase
import com.tradingplatform.app.domain.usecase.risk.GetPortfolioCircuitBreakerStatusUseCase
import com.tradingplatform.app.ui.common.QuoteFallbackController
import com.tradingplatform.app.vpn.VpnNotConnectedException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

// ── UiState definitions ──────────────────────────────────────────────────────

sealed interface NavUiState {
    data object Loading : NavUiState
    data class Success(val data: NavSummary) : NavUiState
    data class Error(val message: String) : NavUiState
}

sealed interface PnlUiState {
    data object Loading : PnlUiState
    data class Success(val data: PnlSummary) : PnlUiState
    data class Error(val message: String) : PnlUiState
}

sealed interface QuoteUiState {
    data object Loading : QuoteUiState
    data class Success(val data: Quote) : QuoteUiState

    /**
     * Dernière valeur connue, affichée quand le VPN est inactif ou le WS en échec.
     * Indique visuellement que le cours n'est plus live.
     */
    data class Stale(val data: Quote) : QuoteUiState
    data class Error(val message: String) : QuoteUiState
}

data class DashboardUiState(
    val navSummary: NavUiState = NavUiState.Loading,
    val pnlSummary: PnlUiState = PnlUiState.Loading,
    val quote: QuoteUiState = QuoteUiState.Loading,
    val portfolioId: String = "",
    val selectedPeriod: PnlPeriod = PnlPeriod.DAY,

    /**
     * True quand le flux WS privé (portfolio updates) est en erreur.
     * L'UI peut afficher un indicateur discret (ex: icône WS barrée) sans bloquer.
     * Les données affichées restent valides (polling REST actif en fallback).
     */
    val wsPrivateDegraded: Boolean = false,

    /**
     * Number of strategies currently flagged ``is_active=true`` for the user's
     * portfolio. ``null`` while the value has not yet been fetched (or the
     * fetch failed) — the Dashboard hides the tile in that case rather than
     * showing a misleading "0 stratégies".
     */
    val activeStrategyCount: Int? = null,

    /**
     * Latest circuit-breaker status for the user's portfolio. ``null`` while
     * the value has not yet been fetched. The Dashboard renders a badge from
     * the result (hidden if disabled, green if closed, red if open, amber if
     * Redis was unreachable).
     */
    val circuitBreakerStatus: PortfolioCircuitBreakerStatus? = null,
)

/**
 * Délai initial de retry pour le flux WS privé (portfolio updates).
 * Backoff exponentiel : 5s → 10s → 20s → 40s → 60s max.
 */
private const val WS_RETRY_INITIAL_MS = 5_000L
private const val WS_RETRY_MAX_MS = 60_000L
private const val WS_MAX_CONSECUTIVE_FAILURES = 5

private const val TAG = "DashboardViewModel"

/** Debounce avant d'afficher "Deconnecte" pour eviter le flicker (F5). */
private const val WS_STATE_DEBOUNCE_MS = 2_000L

/** Maximum number of activity items retained in the feed. */
private const val ACTIVITY_FEED_MAX_ITEMS = 12

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val getPnlUseCase: GetPnlUseCase,
    private val getPortfolioNavUseCase: GetPortfolioNavUseCase,
    private val getQuoteUseCase: GetQuoteUseCase,
    private val getQuoteStreamUseCase: GetQuoteStreamUseCase,
    private val getDefaultQuoteSymbolUseCase: GetDefaultQuoteSymbolUseCase,
    private val getPortfolioIdUseCase: GetPortfolioIdUseCase,
    private val getPortfolioWsUpdatesUseCase: GetPortfolioWsUpdatesUseCase,
    getWsConnectionStateUseCase: GetWsConnectionStateUseCase,
    private val getActivityFeedUseCase: GetActivityFeedUseCase,
    private val getActiveStrategyCountUseCase: GetActiveStrategyCountUseCase,
    private val getPortfolioCircuitBreakerStatusUseCase: GetPortfolioCircuitBreakerStatusUseCase,
    getPublicWsConnectionStateUseCase: GetPublicWsConnectionStateUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    /**
     * Etat de connexion WS prive avec debounce de 2s pour eviter le flicker (F5).
     *
     * Les transitions Connected -> Connecting -> Connected rapides (< 2s) ne sont
     * pas propagees a l'UI. Seules les deconnexions durables sont affichees.
     * Le debounce ne s'applique qu'aux etats non-Connected : [WsConnectionState.Connected]
     * est propage immediatement pour montrer que la connexion est retablie.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val wsConnectionState: StateFlow<WsConnectionState> = getWsConnectionStateUseCase()
        // Connected est propage immediatement (pas de debounce pour le retour a la normale)
        // Disconnected/Connecting sont debounces pour eviter le flicker
        .debounce { state ->
            if (state == WsConnectionState.Connected) 0L else WS_STATE_DEBOUNCE_MS
        }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = WsConnectionState.Connecting,
        )

    // ── Activity feed (real-time merged WS events) ────────────────────────────

    private val _activityItems = MutableStateFlow<List<ActivityItem>>(emptyList())

    /** Immutable activity feed exposed to the UI — max [ACTIVITY_FEED_MAX_ITEMS] items. */
    val activityItems: StateFlow<List<ActivityItem>> = _activityItems.asStateFlow()

    /**
     * Whether the WebSocket is live (Connected). Derived from [wsConnectionState] so
     * the activity card can show a "En direct" badge when true.
     */
    val isWsLive: StateFlow<Boolean> = wsConnectionState
        .map { it == WsConnectionState.Connected }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = false,
        )

    /** État de la connexion WS publique (cours) — consulté par [refresh]. */
    private val publicWsState: StateFlow<WsConnectionState> = getPublicWsConnectionStateUseCase()

    /**
     * Cours du Dashboard : flux WS public + fallback REST piloté par [publicWsState]
     * (audit #13/#14). Le polling 30 s ne tourne que si le WS n'est pas Connected
     * (debounce 2 s) et que l'app est au premier plan ; il s'arrête dès la reconnexion.
     */
    private val quoteFallback = QuoteFallbackController(
        scope = viewModelScope,
        connectionState = publicWsState,
        isForeground = getPublicWsConnectionStateUseCase.isAppForeground(),
        stream = { symbol -> getQuoteStreamUseCase(symbol) },
        fetch = { symbol -> getQuoteUseCase(symbol) },
        onQuote = { _, quote -> _uiState.update { it.copy(quote = QuoteUiState.Success(quote)) } },
        onStale = { markQuoteStale() },
        onFetchError = { _, e -> onQuoteFetchError(e) },
    )

    /** Job de surveillance du cours (WS + fallback REST). */
    private var quoteWatchJob: Job? = null

    /**
     * Job de collection du flux WS privé (portfolio updates) — relancé automatiquement
     * en cas d'erreur avec backoff exponentiel (R4 fix).
     */
    private var wsPrivateJob: Job? = null

    /**
     * Compteur d'échecs consécutifs du flux WS privé — utilisé pour le backoff
     * et pour arrêter les retries après [WS_MAX_CONSECUTIVE_FAILURES] échecs.
     */
    private var wsPrivateFailures = 0

    /**
     * Guard pour ignorer les appels [refresh] redondants (P5 fix).
     * AtomicBoolean car refresh() peut être appelé depuis plusieurs sources simultanément
     * (pull-to-refresh UI, snackbar retry). Évite les doubles requêtes NAV/PnL.
     */
    private val _isRefreshing = AtomicBoolean(false)

    /**
     * Symbole effectivement suivi pour le cours du Dashboard — résolu au démarrage par
     * [GetDefaultQuoteSymbolUseCase] (préférence utilisateur > premier symbole watchlist
     * > [AppDefaults.DEFAULT_QUOTE_SYMBOL]). Consommé par [refresh] pour rejouer un fetch
     * REST quand le polling fallback est actif.
     */
    @Volatile
    private var dashboardQuoteSymbol: String = ""

    init {
        viewModelScope.launch {
            val portfolioId = getPortfolioIdUseCase()
            _uiState.update { it.copy(portfolioId = portfolioId) }

            // Load NAV, PnL, strategy count and circuit-breaker status in parallel
            // (one-shot on init, period default DAY).
            launch { fetchNav(portfolioId) }
            launch { fetchPnl(portfolioId, PnlPeriod.DAY) }
            launch { fetchStrategyCount(portfolioId) }
            launch { fetchCircuitBreakerStatus(portfolioId) }
        }

        // Collect real-time portfolio updates from the private WebSocket.
        // On chaque portfolio_update, re-fetch NAV et PnL pour avoir les données fraîches.
        // Relancé automatiquement en cas d'erreur avec backoff (R4 fix).
        startWsPrivateCollection()

        // Démarrer l'abonnement WS public pour les cours en temps réel.
        // Symbole résolu via [GetDefaultQuoteSymbolUseCase] — préférence utilisateur,
        // sinon premier symbole de la watchlist, sinon fallback hardcodé.
        // Si le WS public n'est pas Connected (VPN coupé, serveur injoignable), le
        // QuoteFallbackController bascule sur le polling REST puis l'arrête à la reconnexion.
        viewModelScope.launch {
            val symbol = getDefaultQuoteSymbolUseCase()
            dashboardQuoteSymbol = symbol
            startWsQuoteSubscription(symbol)
        }

        // Collect merged activity feed from all WS streams.
        // New items are prepended; list is capped at ACTIVITY_FEED_MAX_ITEMS.
        startActivityFeedCollection()
    }

    /**
     * Démarre (ou relance) la collection du flux WS privé (portfolio updates).
     *
     * Sur chaque événement, déclenche un re-fetch REST de NAV et PnL pour
     * avoir les données fraîches avec les valeurs désérialisées proprement.
     *
     * Stratégie délibérée : le payload WS brut (JSONObject) n'est pas
     * mappé directement sur [NavUiState] pour éviter de dupliquer la logique
     * de désérialisation. Le WS sert de signal de fraîcheur, le REST fournit
     * les données structurées.
     *
     * En cas d'erreur du flux (R4 fix) :
     * 1. Émet [DashboardUiState.wsPrivateDegraded] = true (indicateur discret, pas de pop-up)
     * 2. Relance automatiquement après un délai backoff exponentiel
     * 3. Arrête les retries après [WS_MAX_CONSECUTIVE_FAILURES] échecs consécutifs
     *    pour éviter une boucle infinie (le polling REST reste le fallback)
     *
     * Quand la collection reprend avec succès, reset le compteur d'échecs et
     * repasse [wsPrivateDegraded] à false.
     */
    private fun startWsPrivateCollection() {
        wsPrivateJob?.cancel()
        wsPrivateJob = viewModelScope.launch {
            getPortfolioWsUpdatesUseCase()
                .debounce(500L)  // ignorer les rafales — max 1 update/500ms
                .catch { e ->
                    Timber.tag(TAG).w(e, "DashboardViewModel: WS private flow error — marking degraded")
                    _uiState.update { it.copy(wsPrivateDegraded = true) }
                    wsPrivateFailures++

                    if (wsPrivateFailures < WS_MAX_CONSECUTIVE_FAILURES) {
                        val delayMs = minOf(
                            WS_RETRY_INITIAL_MS * (1L shl (wsPrivateFailures - 1)),
                            WS_RETRY_MAX_MS,
                        )
                        Timber.tag(TAG).d(
                            "DashboardViewModel: retrying WS private in ${delayMs}ms " +
                                "(failure #$wsPrivateFailures/$WS_MAX_CONSECUTIVE_FAILURES)"
                        )
                        delay(delayMs)
                        startWsPrivateCollection()
                    } else {
                        Timber.tag(TAG).w(
                            "DashboardViewModel: WS private max retries reached " +
                                "($WS_MAX_CONSECUTIVE_FAILURES) — relying on REST polling only"
                        )
                    }
                }
                .collect {
                    // Collection réussie — reset le compteur et le flag dégradé
                    if (wsPrivateFailures > 0) {
                        wsPrivateFailures = 0
                        _uiState.update { it.copy(wsPrivateDegraded = false) }
                        Timber.tag(TAG).d("DashboardViewModel: WS private recovered — degraded flag cleared")
                    }

                    Timber.tag(TAG).d("DashboardViewModel: portfolio_update received via WS — refreshing NAV/PnL")
                    val portfolioId = _uiState.value.portfolioId
                    val period = _uiState.value.selectedPeriod
                    viewModelScope.launch { fetchNav(portfolioId) }
                    viewModelScope.launch { fetchPnl(portfolioId, period) }
                }
        }
    }

    /**
     * Démarre la surveillance du cours [symbol] : flux WS public (jamais annulé sur coupure →
     * resouscription automatique) + polling REST 30 s tant que le WS public n'est pas
     * Connected et que l'app est au premier plan (cf. [quoteFallback]).
     *
     * La souscription WS est ref-comptée côté client : si MarketData suit le même symbole
     * et le retire de la watchlist, le Dashboard garde sa propre souscription (audit #13).
     */
    private fun startWsQuoteSubscription(symbol: String) {
        quoteWatchJob?.cancel()
        Timber.tag(TAG).d("DashboardViewModel: watching quote $symbol (WS public + REST fallback)")
        quoteWatchJob = quoteFallback.watch(symbol)
    }

    /** WS public non live (debouncé) — le dernier cours affiché passe en [QuoteUiState.Stale]. */
    private fun markQuoteStale() {
        _uiState.update { state ->
            val prev = state.quote
            if (prev is QuoteUiState.Success) state.copy(quote = QuoteUiState.Stale(prev.data)) else state
        }
    }

    /**
     * Collects the merged activity feed from [GetActivityFeedUseCase].
     *
     * New items are prepended to the list so the most recent appear first.
     * The list is capped at [ACTIVITY_FEED_MAX_ITEMS] to avoid unbounded growth.
     * Errors are silently caught and logged — the activity feed is non-critical
     * and should not degrade the rest of the Dashboard.
     */
    private fun startActivityFeedCollection() {
        viewModelScope.launch {
            getActivityFeedUseCase()
                .catch { e ->
                    Timber.tag(TAG).w(e, "DashboardViewModel: activity feed flow error — ignoring")
                }
                .collect { item ->
                    _activityItems.update { current ->
                        (listOf(item) + current).take(ACTIVITY_FEED_MAX_ITEMS)
                    }
                }
        }
    }

    // ── Public actions ────────────────────────────────────────────────────────

    fun selectPeriod(period: PnlPeriod) {
        _uiState.update { it.copy(selectedPeriod = period) }
        val portfolioId = _uiState.value.portfolioId
        viewModelScope.launch { fetchPnl(portfolioId, period) }
    }

    fun refresh() {
        val portfolioId = _uiState.value.portfolioId
        if (portfolioId.isEmpty()) return  // init not complete yet — portfolioId not loaded
        // Guard : ignorer les appels redondants (5 swipes rapides = 1 seule requête — P5 fix)
        if (!_isRefreshing.compareAndSet(false, true)) return
        val period = _uiState.value.selectedPeriod
        viewModelScope.launch {
            try {
                // coroutineScope suspends until both children complete — without it,
                // launch{} returns immediately and the finally block would reset the
                // guard before the fetches finish, making compareAndSet ineffective.
                coroutineScope {
                    launch { fetchNav(portfolioId) }
                    launch { fetchPnl(portfolioId, period) }
                    launch { fetchStrategyCount(portfolioId) }
                    launch { fetchCircuitBreakerStatus(portfolioId) }
                }
            } finally {
                _isRefreshing.set(false)
            }
        }
        // Pour le cours : forcer un fetch REST immédiat si le WS public n'est pas live
        // (mode fallback) ; si le WS est Connected le prochain update arrivera naturellement.
        if (publicWsState.value != WsConnectionState.Connected && dashboardQuoteSymbol.isNotEmpty()) {
            viewModelScope.launch { fetchQuote(dashboardQuoteSymbol) }
        }
    }

    // ── Private fetch helpers ─────────────────────────────────────────────────

    private suspend fun fetchNav(portfolioId: String) {
        _uiState.update { it.copy(navSummary = NavUiState.Loading) }
        getPortfolioNavUseCase(portfolioId)
            .onSuccess { nav ->
                _uiState.update { it.copy(navSummary = NavUiState.Success(nav)) }
            }
            .onFailure { e ->
                _uiState.update {
                    it.copy(navSummary = NavUiState.Error(e.localizedMessage ?: "Erreur"))
                }
            }
    }

    private suspend fun fetchPnl(portfolioId: String, period: PnlPeriod) {
        _uiState.update { it.copy(pnlSummary = PnlUiState.Loading) }
        getPnlUseCase(portfolioId, period)
            .onSuccess { pnl ->
                _uiState.update { it.copy(pnlSummary = PnlUiState.Success(pnl)) }
            }
            .onFailure { e ->
                _uiState.update {
                    it.copy(pnlSummary = PnlUiState.Error(e.localizedMessage ?: "Erreur"))
                }
            }
    }

    /**
     * Fetch the active-strategy count. Failures are silent (the tile is hidden when
     * [DashboardUiState.activeStrategyCount] is null) — a missing count must not
     * block the rest of the Dashboard from rendering.
     */
    private suspend fun fetchStrategyCount(portfolioId: String) {
        getActiveStrategyCountUseCase(portfolioId)
            .onSuccess { count ->
                _uiState.update { it.copy(activeStrategyCount = count) }
            }
            .onFailure { e ->
                Timber.tag(TAG).w(e, "DashboardViewModel: strategy count fetch failed")
            }
    }

    /**
     * Fetch the portfolio circuit-breaker status. Failures are silent (the badge
     * is hidden when [DashboardUiState.circuitBreakerStatus] is null) — the
     * Dashboard's main job is showing the P&L, not nagging on a missing badge.
     */
    private suspend fun fetchCircuitBreakerStatus(portfolioId: String) {
        getPortfolioCircuitBreakerStatusUseCase(portfolioId)
            .onSuccess { status ->
                _uiState.update { it.copy(circuitBreakerStatus = status) }
            }
            .onFailure { e ->
                Timber.tag(TAG).w(e, "DashboardViewModel: circuit-breaker status fetch failed")
            }
    }

    /** One-shot REST fetch of the quote (pull-to-refresh while the WS public is down). */
    private suspend fun fetchQuote(symbol: String) {
        getQuoteUseCase(symbol)
            .onSuccess { quote ->
                _uiState.update { it.copy(quote = QuoteUiState.Success(quote)) }
            }
            .onFailure { e -> onQuoteFetchError(e) }
    }

    /**
     * REST quote fetch failure (fallback polling or refresh). Three distinct cases (CLAUDE.md §2):
     * - VpnNotConnectedException → transition to Stale (keep last value) — not a blocking error
     * - SocketTimeoutException / IOException → transient, keep previous state
     * - Other → display error
     */
    private fun onQuoteFetchError(e: Throwable) {
        when (e) {
            // VPN coupé — garder la valeur précédente, pas d'erreur bloquante
            is VpnNotConnectedException -> markQuoteStale()
            // Transitoire — garder l'état précédent sans modification
            is SocketTimeoutException, is IOException -> Unit
            else -> _uiState.update {
                it.copy(quote = QuoteUiState.Error(e.localizedMessage ?: "Erreur"))
            }
        }
    }
}
