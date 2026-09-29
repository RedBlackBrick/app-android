package com.tradingplatform.app.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.model.ActivityItem
import com.tradingplatform.app.domain.model.NavCurve
import com.tradingplatform.app.domain.model.NavSummary
import com.tradingplatform.app.domain.model.PerformanceMetrics
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.PnlSummary
import com.tradingplatform.app.domain.model.Portfolio
import com.tradingplatform.app.domain.model.PortfolioBrokerStatus
import com.tradingplatform.app.domain.model.PortfolioCircuitBreakerStatus
import com.tradingplatform.app.domain.model.PortfolioOverviewItem
import com.tradingplatform.app.domain.model.RiskStatus
import com.tradingplatform.app.domain.model.WsConnectionState
import com.tradingplatform.app.domain.model.WsUpdate
import com.tradingplatform.app.domain.usecase.activity.GetActivityFeedUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetActiveStrategyCountUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetNavCurveUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPerformanceUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPnlUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPortfolioBrokerStatusUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPortfolioNavUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPortfolioWsUpdatesUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPortfoliosOverviewUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetWsConnectionStateUseCase
import com.tradingplatform.app.domain.usecase.portfolio.ObserveActivePortfolioUseCase
import com.tradingplatform.app.domain.usecase.portfolio.ObservePortfoliosUseCase
import com.tradingplatform.app.domain.usecase.portfolio.RefreshPortfoliosUseCase
import com.tradingplatform.app.domain.usecase.portfolio.SelectPortfolioUseCase
import com.tradingplatform.app.domain.usecase.risk.GetPortfolioCircuitBreakerStatusUseCase
import com.tradingplatform.app.domain.usecase.risk.GetRiskStatusUseCase
import com.tradingplatform.app.ui.common.DataState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.math.BigDecimal
import javax.inject.Inject

// ── UiState definitions ──────────────────────────────────────────────────────

/**
 * État de l'Accueil pour le portefeuille ACTIF ([portfolioId]). Au changement de portefeuille
 * actif, TOUT ce qui concerne l'ancien portefeuille est remis à zéro avant le rechargement
 * (cf. [DashboardViewModel]) — seules la période choisie, l'indicateur WS et la carte « Mes
 * portefeuilles » (niveau compte, pas portefeuille) survivent.
 */
data class DashboardUiState(
    /**
     * NAV du portfolio. La valeur n'est jamais remise à `null` après un premier succès
     * (refresh WS / pull-to-refresh / échec → valeur conservée, cf. [DataState]).
     * Patchée directement par chaque `portfolio_update` WS avant le refetch REST debouncé.
     */
    val navSummary: DataState<NavSummary> = DataState(isRefreshing = true),
    /** P&L de [selectedPeriod] — même contrat que [navSummary] (hors changement de période). */
    val pnlSummary: DataState<PnlSummary> = DataState(isRefreshing = true),
    /**
     * Courbe de NAV de [selectedPeriod] (héros). Sans valeur (absente, vide ou en échec), l'écran
     * ne dessine aucune courbe. Réinitialisée à chaque changement de période.
     */
    val navCurve: DataState<NavCurve> = DataState(isRefreshing = true),
    val portfolioId: String = "",
    val selectedPeriod: PnlPeriod = PnlPeriod.DAY,

    /**
     * True quand le flux WS privé (portfolio updates) est en erreur.
     * L'UI peut afficher un indicateur discret (ex: icône WS barrée) sans bloquer.
     * Les données affichées restent valides (polling REST actif en fallback).
     */
    val wsPrivateDegraded: Boolean = false,

    /**
     * Number of strategies currently flagged ``is_active=true`` for the active
     * portfolio. ``null`` while the value has not yet been fetched (or the
     * fetch failed) — the Dashboard hides the entry in that case rather than
     * showing a misleading "0 stratégie".
     */
    val activeStrategyCount: Int? = null,

    /**
     * Latest circuit-breaker status for the active portfolio. ``null`` while the value has not yet
     * been fetched. Ne s'affiche jamais seul : il alimente le bandeau de risque unique
     * ([riskBannerModel]) avec [riskStatus].
     */
    val circuitBreakerStatus: PortfolioCircuitBreakerStatus? = null,

    /**
     * Situation de risque du portefeuille actif (kill switch, violations, perte du jour). `null`
     * tant qu'elle n'a pas été lue avec succès ; un échec de rafraîchissement conserve la dernière
     * valeur (un bandeau d'alerte déjà affiché ne disparaît pas sur une simple erreur réseau).
     */
    val riskStatus: RiskStatus? = null,

    /**
     * Métriques de performance cumulées (win rate, drawdown max — FRACTIONS) des tuiles KPI.
     * `null` = échec ou pas encore lues → les deux tuiles sont omises.
     */
    val performance: PerformanceMetrics? = null,

    /** Connexion broker du portefeuille actif ; `null` = aucune connexion ou lecture en échec. */
    val brokerStatus: PortfolioBrokerStatus? = null,

    /**
     * Valeur + P&L de [selectedPeriod] pour TOUS les portefeuilles du compte (carte « Mes
     * portefeuilles », seulement si le compte en a ≥ 2). Niveau compte : n'est PAS remis à zéro au
     * changement de portefeuille actif (la ligne active est simplement re-surlignée) mais l'est au
     * changement de période, pour ne jamais montrer un P&L sous le mauvais libellé de période.
     */
    val portfolioOverview: DataState<List<PortfolioOverviewItem>> = DataState(),
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

/**
 * Debounce du refetch REST NAV + PnL déclenché par les `portfolio_update` WS : une rafale
 * d'exécutions (ordre fractionné, rebalancing) ne produit qu'un seul couple de requêtes.
 */
internal const val WS_REFETCH_DEBOUNCE_MS = 750L

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val getPnlUseCase: GetPnlUseCase,
    private val getPortfolioNavUseCase: GetPortfolioNavUseCase,
    private val observeActivePortfolioUseCase: ObserveActivePortfolioUseCase,
    observePortfoliosUseCase: ObservePortfoliosUseCase,
    private val refreshPortfoliosUseCase: RefreshPortfoliosUseCase,
    private val selectPortfolioUseCase: SelectPortfolioUseCase,
    private val getPortfolioWsUpdatesUseCase: GetPortfolioWsUpdatesUseCase,
    getWsConnectionStateUseCase: GetWsConnectionStateUseCase,
    private val getActivityFeedUseCase: GetActivityFeedUseCase,
    private val getActiveStrategyCountUseCase: GetActiveStrategyCountUseCase,
    private val getPortfolioCircuitBreakerStatusUseCase: GetPortfolioCircuitBreakerStatusUseCase,
    private val getNavCurveUseCase: GetNavCurveUseCase,
    private val getPerformanceUseCase: GetPerformanceUseCase,
    private val getPortfoliosOverviewUseCase: GetPortfoliosOverviewUseCase,
    private val getRiskStatusUseCase: GetRiskStatusUseCase,
    private val getPortfolioBrokerStatusUseCase: GetPortfolioBrokerStatusUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    /**
     * Portefeuilles du compte (vide tant que la liste n'a pas été chargée). Sert au sélecteur de
     * la barre haute, à la carte « Mes portefeuilles » (≥ 2) et à la devise du portefeuille actif.
     * L'id du portefeuille actif est [DashboardUiState.portfolioId].
     */
    val portfolios: StateFlow<List<Portfolio>> = observePortfoliosUseCase()

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
     * Rafraîchissement manuel en cours (pull-to-refresh / « Réessayer »). Sert de garde : un appel
     * de [refresh] pendant qu'un autre tourne est ignoré (5 swipes rapides = 1 seule série de
     * requêtes — P5 fix). Annulé au changement de portefeuille actif.
     */
    private var refreshJob: Job? = null

    /** Chargement de la section « période » (P&L + courbe) en cours — annulé par un nouveau choix. */
    private var periodJob: Job? = null

    /** Lecture de « Mes portefeuilles » en cours — remplacée à chaque nouveau déclencheur. */
    private var overviewJob: Job? = null

    /**
     * Signal « un portfolio_update WS est arrivé » → refetch REST NAV + PnL debouncé de
     * [WS_REFETCH_DEBOUNCE_MS]. Buffer 1 / DROP_OLDEST : `tryEmit` ne suspend jamais et une
     * rafale se réduit à un seul signal.
     */
    private val wsRefetchTrigger = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        // Peuple la liste des portefeuilles (vide tant que personne ne l'appelle) — résultat ignoré
        // sauf log : l'écran fonctionne avec le portefeuille actif même sans la liste.
        viewModelScope.launch { refreshPortfolios() }

        // Portefeuille actif → reset complet PUIS rechargement. collectLatest annule les chargements
        // en vol de l'ancien portefeuille dès qu'un nouvel id arrive.
        viewModelScope.launch {
            observeActivePortfolioUseCase().collectLatest { id -> onActivePortfolioChanged(id) }
        }

        // Carte « Mes portefeuilles » : chargée dès que le compte en a ≥ 2.
        startPortfolioListCollection()

        // Collect real-time portfolio updates from the private WebSocket.
        // Chaque portfolio_update patche la NAV immédiatement puis déclenche un refetch
        // REST NAV + PnL debouncé (valeurs conservées pendant le refetch — audit #21).
        // Relancé automatiquement en cas d'erreur avec backoff (R4 fix).
        startWsRefetchCollection()
        startWsPrivateCollection()

        // Collect merged activity feed from all WS streams.
        // New items are prepended; list is capped at ACTIVITY_FEED_MAX_ITEMS.
        startActivityFeedCollection()
    }

    // ── Portefeuille actif ────────────────────────────────────────────────────

    /**
     * Le portefeuille actif vient de changer (ou vient d'être connu) : remet à zéro TOUT l'état
     * lié à l'ancien portefeuille AVANT de recharger — jamais de NAV, P&L, courbe, risque, broker,
     * KPI ni activité de l'ancien portefeuille sous le nouveau. La période choisie reste (préférence
     * de l'utilisateur) ; la carte « Mes portefeuilles » aussi (niveau compte).
     *
     * Suspend jusqu'à la fin du chargement : c'est ce qui permet à `collectLatest` de l'annuler
     * proprement si le portefeuille change encore.
     */
    private suspend fun onActivePortfolioChanged(portfolioId: String) {
        refreshJob?.cancel()
        refreshJob = null
        periodJob?.cancel()
        periodJob = null
        _activityItems.value = emptyList()
        _uiState.update { previous ->
            DashboardUiState(
                portfolioId = portfolioId,
                selectedPeriod = previous.selectedPeriod,
                wsPrivateDegraded = previous.wsPrivateDegraded,
                portfolioOverview = previous.portfolioOverview,
            )
        }
        loadPortfolioData(portfolioId, _uiState.value.selectedPeriod)
    }

    /**
     * Charge en parallèle toutes les sections du portefeuille [portfolioId] pour [period] :
     * NAV, P&L, courbe, performance, stratégies, circuit-breaker, risque, broker. Chaque section
     * est indépendante : l'échec de l'une ne bloque pas les autres.
     */
    private suspend fun loadPortfolioData(portfolioId: String, period: PnlPeriod) {
        // coroutineScope suspend jusqu'à la fin de tous les enfants — sans lui, launch{} rendrait
        // la main tout de suite (le garde de refresh() et l'annulation par collectLatest seraient vains).
        coroutineScope {
            launch { fetchNav(portfolioId) }
            launch { fetchPnl(portfolioId, period) }
            launch { fetchNavCurve(portfolioId, period) }
            launch { fetchPerformance(portfolioId) }
            launch { fetchStrategyCount(portfolioId) }
            launch { fetchCircuitBreakerStatus(portfolioId) }
            launch { fetchRiskStatus(portfolioId) }
            launch { fetchBrokerStatus(portfolioId) }
        }
    }

    private suspend fun refreshPortfolios() {
        refreshPortfoliosUseCase().onFailure { e ->
            Timber.tag(TAG).w(e, "DashboardViewModel: portfolio list refresh failed")
        }
    }

    /**
     * Suit la liste des portefeuilles (par ids, sans compter les simples changements de nom) :
     * ≥ 2 → charge « Mes portefeuilles » ; < 2 → efface la carte. À l'ouverture de l'écran la
     * liste est encore vide (elle arrive après [refreshPortfolios]) : la carte se charge alors à
     * son arrivée, sans requête inutile pour un compte à un seul portefeuille.
     */
    private fun startPortfolioListCollection() {
        viewModelScope.launch {
            portfolios
                .map { list -> list.map { it.id } }
                .distinctUntilChanged()
                .collect { ids ->
                    if (ids.size >= 2) {
                        fetchOverview(_uiState.value.selectedPeriod)
                    } else {
                        overviewJob?.cancel()
                        _uiState.update { it.copy(portfolioOverview = DataState()) }
                    }
                }
        }
    }

    /**
     * Démarre (ou relance) la collection du flux WS privé (portfolio updates).
     *
     * Sur chaque événement (audit #21) :
     * 1. `total_value` / `cash_balance` / `positions_value` sont appliqués directement à la
     *    NAV affichée (patch optimiste, `syncedAt = now`) — le backend n'envoie pas de P&L
     *    sur ce canal, la PnL n'est donc pas patchée ;
     * 2. NAV et PnL passent en `isRefreshing` (valeurs conservées, pas de skeleton) ;
     * 3. un refetch REST NAV + PnL est demandé via [wsRefetchTrigger], debouncé de
     *    [WS_REFETCH_DEBOUNCE_MS] (cf. [startWsRefetchCollection]) — il fournit les champs
     *    absents du WS (P&L réalisé/latent, métriques PnL) et corrige un éventuel écart.
     *
     * Un événement d'un AUTRE portefeuille que l'actif est ignoré ([onPortfolioUpdate]).
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
                .collect { update ->
                    // Collection réussie — reset le compteur et le flag dégradé
                    if (wsPrivateFailures > 0) {
                        wsPrivateFailures = 0
                        _uiState.update { it.copy(wsPrivateDegraded = false) }
                        Timber.tag(TAG).d("DashboardViewModel: WS private recovered — degraded flag cleared")
                    }
                    onPortfolioUpdate(update)
                }
        }
    }

    /**
     * Applique un `portfolio_update` WS : patch NAV direct + passage en refresh de NAV et
     * PnL, puis demande le refetch REST debouncé. Les montants ne sont jamais loggés.
     *
     * Les événements d'un autre portefeuille (`portfolioId` non nul et différent de l'actif) sont
     * ignorés : ils ne patchent pas la NAV affichée et ne déclenchent aucun refetch. Un événement
     * sans `portfolioId` est traité comme celui du portefeuille actif (comportement historique).
     */
    private fun onPortfolioUpdate(update: WsUpdate.PortfolioUpdate) {
        val activeId = _uiState.value.portfolioId
        if (update.portfolioId != null && update.portfolioId != activeId) {
            Timber.tag(TAG).d("DashboardViewModel: portfolio_update for another portfolio — ignored")
            return
        }
        Timber.tag(TAG).d("DashboardViewModel: portfolio_update received via WS — patching NAV, refetch scheduled")
        val now = System.currentTimeMillis()
        _uiState.update { state ->
            val nav = state.navSummary
            val patched = nav.value?.patchedWith(update)
            val newNav = if (patched != null && patched != nav.value) {
                nav.copy(value = patched, syncedAt = now)
            } else {
                nav
            }
            state.copy(
                navSummary = newNav.loading(),
                pnlSummary = state.pnlSummary.loading(),
            )
        }
        wsRefetchTrigger.tryEmit(Unit)
    }

    /**
     * Refetch REST NAV + PnL déclenché par le WS privé, debouncé de [WS_REFETCH_DEBOUNCE_MS] :
     * N `portfolio_update` rapprochés → une seule paire de requêtes. `collect` séquentiel :
     * un signal reçu pendant un refetch en vol est traité après lui (jamais annulé).
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun startWsRefetchCollection() {
        viewModelScope.launch {
            wsRefetchTrigger
                .debounce(WS_REFETCH_DEBOUNCE_MS)
                .collect {
                    val state = _uiState.value
                    if (state.portfolioId.isEmpty()) return@collect
                    coroutineScope {
                        launch { fetchNav(state.portfolioId) }
                        launch { fetchPnl(state.portfolioId, state.selectedPeriod) }
                    }
                }
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

    /**
     * Change la période du héros. Seule la section « période » repart de zéro : P&L, courbe de NAV
     * et « Mes portefeuilles » (dont le P&L dépend de la période) — la NAV garde sa valeur → pas de
     * skeleton global. Re-sélectionner la période courante = simple refresh, valeurs conservées.
     */
    fun selectPeriod(period: PnlPeriod) {
        // Changement de période : la PnL de l'ancienne période ne doit pas rester affichée
        // sous la nouvelle puce (ni comme valeur « périmée » si le fetch échoue) → retour à
        // l'état initial de la section, jamais le P&L / la courbe d'une autre période.
        val multiPortfolio = portfolios.value.size >= 2
        _uiState.update {
            if (it.selectedPeriod == period) {
                it
            } else {
                it.copy(
                    selectedPeriod = period,
                    pnlSummary = DataState(isRefreshing = true),
                    navCurve = DataState(isRefreshing = true),
                    portfolioOverview = if (multiPortfolio) DataState(isRefreshing = true) else DataState(),
                )
            }
        }
        val portfolioId = _uiState.value.portfolioId
        if (portfolioId.isNotEmpty()) {
            periodJob?.cancel()
            periodJob = viewModelScope.launch {
                coroutineScope {
                    launch { fetchPnl(portfolioId, period) }
                    launch { fetchNavCurve(portfolioId, period) }
                }
            }
        }
        if (multiPortfolio) fetchOverview(period)
    }

    /** Sélectionne un autre portefeuille (ligne de « Mes portefeuilles ») — no-op si c'est l'actif. */
    fun selectPortfolio(portfolioId: String) {
        viewModelScope.launch {
            selectPortfolioUseCase(portfolioId).onFailure { e ->
                Timber.tag(TAG).w(e, "DashboardViewModel: portfolio selection failed")
            }
        }
    }

    fun refresh() {
        val state = _uiState.value
        val portfolioId = state.portfolioId
        if (portfolioId.isEmpty()) {
            // Portefeuille actif pas encore connu (liste jamais chargée) : on retente de la charger,
            // ce qui fait émettre l'id actif et lance le chargement normal.
            viewModelScope.launch { refreshPortfolios() }
            return
        }
        // Guard : ignorer les appels redondants (5 swipes rapides = 1 seule requête — P5 fix)
        if (refreshJob?.isActive == true) return
        val period = state.selectedPeriod
        val known = portfolios.value
        if (known.isEmpty()) {
            // La liste n'a jamais pu être chargée (ex. tunnel pas encore monté à l'ouverture) :
            // nouvelle tentative — sans elle ni le sélecteur ni « Mes portefeuilles » n'apparaissent.
            viewModelScope.launch { refreshPortfolios() }
        } else if (known.size >= 2) {
            fetchOverview(period)
        }
        // loadPortfolioData suspend jusqu'à la fin de toutes les sections : refreshJob reste actif
        // pendant tout le rafraîchissement, ce qui rend la garde ci-dessus effective.
        refreshJob = viewModelScope.launch { loadPortfolioData(portfolioId, period) }
    }

    // ── Private fetch helpers ─────────────────────────────────────────────────

    /**
     * Applique [transform] à l'état seulement si [portfolioId] est TOUJOURS le portefeuille actif :
     * la réponse (ou le début de chargement) d'un portefeuille que l'utilisateur a quitté entre-temps
     * ne peut jamais toucher l'état du nouveau.
     */
    private fun updateFor(portfolioId: String, transform: (DashboardUiState) -> DashboardUiState) {
        _uiState.update { if (it.portfolioId == portfolioId) transform(it) else it }
    }

    /**
     * Fetch REST de la NAV. La valeur courante est conservée pendant le chargement et
     * après un échec (valeur périmée + erreur) — jamais remise à `null` (audit #21).
     */
    private suspend fun fetchNav(portfolioId: String) {
        updateFor(portfolioId) { it.copy(navSummary = it.navSummary.loading()) }
        getPortfolioNavUseCase(portfolioId)
            .onSuccess { nav ->
                val now = System.currentTimeMillis()
                updateFor(portfolioId) { it.copy(navSummary = it.navSummary.success(nav, now)) }
            }
            .onFailure { e ->
                updateFor(portfolioId) {
                    it.copy(navSummary = it.navSummary.failure(e.localizedMessage ?: "Erreur"))
                }
            }
    }

    /**
     * Fetch REST de la PnL de [period] — même contrat que [fetchNav]. Un résultat arrivé
     * après un changement de période est ignoré (la section appartient à la nouvelle période).
     */
    private suspend fun fetchPnl(portfolioId: String, period: PnlPeriod) {
        updateFor(portfolioId) {
            if (it.selectedPeriod == period) it.copy(pnlSummary = it.pnlSummary.loading()) else it
        }
        getPnlUseCase(portfolioId, period)
            .onSuccess { pnl ->
                val now = System.currentTimeMillis()
                updateFor(portfolioId) {
                    if (it.selectedPeriod != period) it
                    else it.copy(pnlSummary = it.pnlSummary.success(pnl, now))
                }
            }
            .onFailure { e ->
                updateFor(portfolioId) {
                    if (it.selectedPeriod != period) it
                    else it.copy(pnlSummary = it.pnlSummary.failure(e.localizedMessage ?: "Erreur"))
                }
            }
    }

    /**
     * Fetch de la courbe de NAV de [period]. Un échec est silencieux côté UI (le héros n'affiche
     * alors aucune courbe — jamais un tracé de remplacement) ; il est seulement loggé. Un résultat
     * arrivé après un changement de période est ignoré, comme [fetchPnl].
     */
    private suspend fun fetchNavCurve(portfolioId: String, period: PnlPeriod) {
        updateFor(portfolioId) {
            if (it.selectedPeriod == period) it.copy(navCurve = it.navCurve.loading()) else it
        }
        getNavCurveUseCase(portfolioId, period)
            .onSuccess { curve ->
                val now = System.currentTimeMillis()
                updateFor(portfolioId) {
                    if (it.selectedPeriod != period) it
                    else it.copy(navCurve = it.navCurve.success(curve, now))
                }
            }
            .onFailure { e ->
                Timber.tag(TAG).w(e, "DashboardViewModel: NAV curve fetch failed")
                updateFor(portfolioId) {
                    if (it.selectedPeriod != period) it
                    else it.copy(navCurve = it.navCurve.failure(e.localizedMessage ?: "Erreur"))
                }
            }
    }

    /**
     * Fetch des métriques de performance (tuiles Win rate / Drawdown max). Un échec est silencieux :
     * les deux tuiles sont simplement omises (ou gardent leur dernière valeur lors d'un refresh).
     */
    private suspend fun fetchPerformance(portfolioId: String) {
        getPerformanceUseCase(portfolioId)
            .onSuccess { metrics ->
                updateFor(portfolioId) { it.copy(performance = metrics) }
            }
            .onFailure { e ->
                Timber.tag(TAG).w(e, "DashboardViewModel: performance fetch failed")
            }
    }

    /**
     * Fetch the active-strategy count. Failures are silent (the entry is hidden when
     * [DashboardUiState.activeStrategyCount] is null) — a missing count must not
     * block the rest of the Dashboard from rendering.
     */
    private suspend fun fetchStrategyCount(portfolioId: String) {
        getActiveStrategyCountUseCase(portfolioId)
            .onSuccess { count ->
                updateFor(portfolioId) { it.copy(activeStrategyCount = count) }
            }
            .onFailure { e ->
                Timber.tag(TAG).w(e, "DashboardViewModel: strategy count fetch failed")
            }
    }

    /**
     * Fetch the portfolio circuit-breaker status. Failures are silent (it only feeds the risk
     * banner, hidden when nothing is wrong or unknown) — the Dashboard's main job is showing the
     * P&L, not nagging on a missing status.
     */
    private suspend fun fetchCircuitBreakerStatus(portfolioId: String) {
        getPortfolioCircuitBreakerStatusUseCase(portfolioId)
            .onSuccess { status ->
                updateFor(portfolioId) { it.copy(circuitBreakerStatus = status) }
            }
            .onFailure { e ->
                Timber.tag(TAG).w(e, "DashboardViewModel: circuit-breaker status fetch failed")
            }
    }

    /**
     * Fetch de la situation de risque (kill switch, violations, perte du jour). Un échec est
     * silencieux : pas de bandeau (ou le dernier connu lors d'un refresh). Le use case ne fait déjà
     * pas échouer l'ensemble pour une lecture partielle (`RiskStatus.isPartial`).
     */
    private suspend fun fetchRiskStatus(portfolioId: String) {
        getRiskStatusUseCase(portfolioId)
            .onSuccess { status ->
                updateFor(portfolioId) { it.copy(riskStatus = status) }
            }
            .onFailure { e ->
                Timber.tag(TAG).w(e, "DashboardViewModel: risk status fetch failed")
            }
    }

    /** Fetch de la connexion broker ; succès `null` = aucune connexion (pastille absente). */
    private suspend fun fetchBrokerStatus(portfolioId: String) {
        getPortfolioBrokerStatusUseCase(portfolioId)
            .onSuccess { status ->
                updateFor(portfolioId) { it.copy(brokerStatus = status) }
            }
            .onFailure { e ->
                Timber.tag(TAG).w(e, "DashboardViewModel: broker status fetch failed")
            }
    }

    /**
     * Lit « Mes portefeuilles » pour [period] (tous les portefeuilles du compte). Remplace toute
     * lecture précédente en vol ; un résultat arrivé après un changement de période est ignoré.
     * La valeur précédente de la MÊME période est conservée pendant le chargement et après un échec
     * (valeur périmée, comme la NAV).
     */
    private fun fetchOverview(period: PnlPeriod) {
        overviewJob?.cancel()
        overviewJob = viewModelScope.launch {
            _uiState.update {
                if (it.selectedPeriod == period) it.copy(portfolioOverview = it.portfolioOverview.loading()) else it
            }
            getPortfoliosOverviewUseCase(period)
                .onSuccess { items ->
                    val now = System.currentTimeMillis()
                    _uiState.update {
                        if (it.selectedPeriod != period) it
                        else it.copy(portfolioOverview = it.portfolioOverview.success(items, now))
                    }
                }
                .onFailure { e ->
                    Timber.tag(TAG).w(e, "DashboardViewModel: portfolios overview fetch failed")
                    _uiState.update {
                        if (it.selectedPeriod != period) it
                        else it.copy(
                            portfolioOverview = it.portfolioOverview.failure(e.localizedMessage ?: "Erreur"),
                        )
                    }
                }
        }
    }
}

/**
 * Applique les totaux d'un `portfolio_update` WS à la NAV affichée (patch optimiste).
 *
 * - `cash_balance` remplace [NavSummary.cashBalance] ;
 * - `total_value` remplace [NavSummary.currentValue] ; à défaut, `cash + positions_value`
 *   si `positions_value` est présent ;
 * - P&L réalisé / latent inchangés (absents du payload — corrigés par le refetch REST).
 *
 * Champs absents → valeurs courantes conservées (payload partiel toléré).
 */
internal fun NavSummary.patchedWith(update: WsUpdate.PortfolioUpdate): NavSummary {
    val cash = update.cashBalance?.let { BigDecimal.valueOf(it) } ?: cashBalance
    val total = update.totalValue?.let { BigDecimal.valueOf(it) }
        ?: update.positionsValue?.let { cash + BigDecimal.valueOf(it) }
        ?: currentValue
    return copy(currentValue = total, cashBalance = cash)
}
