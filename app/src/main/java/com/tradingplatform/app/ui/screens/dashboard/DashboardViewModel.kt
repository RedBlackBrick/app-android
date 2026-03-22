package com.tradingplatform.app.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.model.AppDefaults
import com.tradingplatform.app.domain.model.NavSummary
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.PnlSummary
import com.tradingplatform.app.domain.model.Quote
import com.tradingplatform.app.domain.model.WsConnectionState
import com.tradingplatform.app.domain.usecase.auth.GetPortfolioIdUseCase
import com.tradingplatform.app.domain.usecase.market.GetQuoteStreamUseCase
import com.tradingplatform.app.domain.usecase.market.GetQuoteUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPnlUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPortfolioNavUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPortfolioWsUpdatesUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetWsConnectionStateUseCase
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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
)

// ── Poll interval (fallback REST) ─────────────────────────────────────────────
private const val QUOTE_POLL_INTERVAL_MS = 30_000L

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

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val getPnlUseCase: GetPnlUseCase,
    private val getPortfolioNavUseCase: GetPortfolioNavUseCase,
    private val getQuoteUseCase: GetQuoteUseCase,
    private val getQuoteStreamUseCase: GetQuoteStreamUseCase,
    private val getPortfolioIdUseCase: GetPortfolioIdUseCase,
    private val getPortfolioWsUpdatesUseCase: GetPortfolioWsUpdatesUseCase,
    getWsConnectionStateUseCase: GetWsConnectionStateUseCase,
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

    /**
     * Job du polling REST en cours — annulé quand le WS public prend le relais,
     * rétabli si le WS échoue.
     */
    private var pollingJob: Job? = null

    /**
     * Job d'abonnement au WS public en cours.
     */
    private var wsQuoteJob: Job? = null

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

    init {
        viewModelScope.launch {
            val portfolioId = getPortfolioIdUseCase()
            _uiState.update { it.copy(portfolioId = portfolioId) }

            // Load NAV and PnL in parallel (one-shot on init, period default DAY)
            launch { fetchNav(portfolioId) }
            launch { fetchPnl(portfolioId, PnlPeriod.DAY) }
        }

        // Collect real-time portfolio updates from the private WebSocket.
        // On chaque portfolio_update, re-fetch NAV et PnL pour avoir les données fraîches.
        // Relancé automatiquement en cas d'erreur avec backoff (R4 fix).
        startWsPrivateCollection()

        // Démarrer l'abonnement WS public pour les cours en temps réel.
        // Si le WS échoue (erreur de connexion, VPN coupé), le fallback polling REST
        // prend le relais via startPollingFallback().
        startWsQuoteSubscription(AppDefaults.DEFAULT_QUOTE_SYMBOL)
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
     * Démarre l'abonnement au WebSocket public pour les cours en temps réel.
     *
     * Le flow [GetQuoteStreamUseCase] active la subscription WS à la collecte
     * et la désactive à l'annulation. Si la connexion WS échoue (exception
     * non transitoire), on bascule sur le polling REST via [startPollingFallback].
     *
     * La gestion VPN est identique au polling : [VpnNotConnectedException] transite
     * le cours vers [QuoteUiState.Stale] sans bloquer l'UI.
     */
    private fun startWsQuoteSubscription(symbol: String) {
        wsQuoteJob?.cancel()
        pollingJob?.cancel()
        pollingJob = null

        wsQuoteJob = viewModelScope.launch {
            try {
                Timber.tag(TAG).d("DashboardViewModel: starting public WS quote subscription for $symbol")
                getQuoteStreamUseCase(symbol).collect { quote ->
                    _uiState.update { it.copy(quote = QuoteUiState.Success(quote)) }
                }
            } catch (e: VpnNotConnectedException) {
                // VPN coupé — garder la valeur précédente, basculer en Stale
                _uiState.update { state ->
                    val newQuote = when (val prev = state.quote) {
                        is QuoteUiState.Success -> QuoteUiState.Stale(prev.data)
                        is QuoteUiState.Stale -> prev
                        else -> state.quote
                    }
                    state.copy(quote = newQuote)
                }
                // Basculer sur le polling REST comme fallback — le VPN peut se reconnecter
                Timber.tag(TAG).w("DashboardViewModel: VPN not connected — falling back to REST polling")
                startPollingFallback(symbol)
            } catch (e: SocketTimeoutException) {
                // Timeout transitoire — basculer sur le polling REST
                Timber.tag(TAG).w("DashboardViewModel: WS quote timeout — falling back to REST polling")
                startPollingFallback(symbol)
            } catch (e: IOException) {
                // Erreur réseau — basculer sur le polling REST
                Timber.tag(TAG).w(e, "DashboardViewModel: WS quote IO error — falling back to REST polling")
                startPollingFallback(symbol)
            } catch (e: Exception) {
                // Autre erreur non transitoire — basculer sur le polling REST
                Timber.tag(TAG).e(e, "DashboardViewModel: unexpected WS error — falling back to REST polling")
                startPollingFallback(symbol)
            }
        }
    }

    /**
     * Polling REST de secours — activé si le WebSocket public est indisponible.
     *
     * Utilise `while(isActive)` dans [viewModelScope] (jamais `repeatOnLifecycle`
     * qui est une extension Lifecycle — non disponible dans un ViewModel).
     * Côté UI, `collectAsStateWithLifecycle()` suspend automatiquement la
     * collection quand l'app est en arrière-plan.
     *
     * Gestion des exceptions (CLAUDE.md §2 pattern polling Dashboard) :
     * - [VpnNotConnectedException] → transition en Stale, poursuite du polling
     * - [SocketTimeoutException] / [IOException] → transitoire, état inchangé
     * - Autre → affiche erreur
     */
    private fun startPollingFallback(symbol: String) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            Timber.tag(TAG).d("DashboardViewModel: starting REST polling fallback for $symbol")
            while (isActive) {
                fetchQuote(symbol)
                delay(QUOTE_POLL_INTERVAL_MS)
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
                }
            } finally {
                _isRefreshing.set(false)
            }
        }
        // Pour le cours : forcer un fetch REST immédiat si on est en mode polling,
        // ou si le WS est actif le prochain update arrivera naturellement.
        if (pollingJob?.isActive == true) {
            viewModelScope.launch { fetchQuote(AppDefaults.DEFAULT_QUOTE_SYMBOL) }
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
     * Fetches a single quote via REST (fallback). Three distinct error cases (CLAUDE.md §2):
     * - VpnNotConnectedException → transition to Stale (keep last value) — not a blocking error
     * - SocketTimeoutException / IOException → transient, keep previous state
     * - Other → display error
     */
    private suspend fun fetchQuote(symbol: String) {
        getQuoteUseCase(symbol)
            .onSuccess { quote ->
                _uiState.update { it.copy(quote = QuoteUiState.Success(quote)) }
            }
            .onFailure { e ->
                when (e) {
                    is VpnNotConnectedException -> {
                        // VPN coupé — garder la valeur précédente, pas d'erreur bloquante
                        _uiState.update { state ->
                            val newQuote = when (val prev = state.quote) {
                                is QuoteUiState.Success -> QuoteUiState.Stale(prev.data)
                                is QuoteUiState.Stale -> prev // already stale, no change
                                else -> state.quote
                            }
                            state.copy(quote = newQuote)
                        }
                    }
                    is SocketTimeoutException, is IOException -> {
                        // Transitoire — garder l'état précédent sans modification
                        Unit
                    }
                    else -> {
                        _uiState.update {
                            it.copy(quote = QuoteUiState.Error(e.localizedMessage ?: "Erreur"))
                        }
                    }
                }
            }
    }
}
