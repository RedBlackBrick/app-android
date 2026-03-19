package com.tradingplatform.app.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.model.NavSummary
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.PnlSummary
import com.tradingplatform.app.domain.model.Quote
import com.tradingplatform.app.domain.usecase.auth.GetPortfolioIdUseCase
import com.tradingplatform.app.domain.usecase.market.GetQuoteUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPnlUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPortfolioNavUseCase
import com.tradingplatform.app.domain.usecase.portfolio.GetPortfolioWsUpdatesUseCase
import com.tradingplatform.app.vpn.VpnNotConnectedException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import java.net.SocketTimeoutException
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
     * Dernière valeur connue, affichée quand le VPN est inactif.
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
)

// ── Default symbol for quote polling ────────────────────────────────────────
private const val DEFAULT_QUOTE_SYMBOL = "AAPL"
private const val QUOTE_POLL_INTERVAL_MS = 30_000L

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val getPnlUseCase: GetPnlUseCase,
    private val getPortfolioNavUseCase: GetPortfolioNavUseCase,
    private val getQuoteUseCase: GetQuoteUseCase,
    private val getPortfolioIdUseCase: GetPortfolioIdUseCase,
    private val getPortfolioWsUpdatesUseCase: GetPortfolioWsUpdatesUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

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
        // Le polling REST ci-dessous reste actif en parallèle comme fallback.
        // Independent top-level launch — cancellation is clear, not nested inside the init launch.
        viewModelScope.launch {
            collectPortfolioWsUpdates()
        }

        // TODO: Replace REST polling with WebSocket subscription for real-time market data.
        //  Server supports: ws(s)://<host>/ws/public with {"action": "subscribe", "symbols": ["AAPL"]}
        //  Message type: "market_data" with bid/ask/mid/timestamp fields.
        //  See trading-platform2 CLAUDE.md WebSocket section for full protocol details.

        // Start quote polling loop — while(isActive) never uses repeatOnLifecycle
        // (which is a Lifecycle extension, not available in ViewModel)
        viewModelScope.launch {
            while (isActive) {
                fetchQuote(DEFAULT_QUOTE_SYMBOL)
                delay(QUOTE_POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Écoute les [WsEvent.PortfolioUpdate] du WebSocket privé.
     *
     * Sur chaque événement, déclenche un re-fetch REST de NAV et PnL pour
     * avoir les données fraîches avec les valeurs désérialisées proprement.
     *
     * Stratégie délibérée : le payload WS brut (JSONObject) n'est pas
     * mappé directement sur [NavUiState] pour éviter de dupliquer la logique
     * de désérialisation. Le WS sert de signal de fraîcheur, le REST fournit
     * les données structurées. Le polling REST reste actif en fallback.
     *
     * Erreurs silencieuses — une update WS manquée est compensée par le
     * prochain cycle de polling.
     *
     * Appelée depuis un top-level [viewModelScope.launch] — ne crée pas de
     * launch imbriqué pour garder la hiérarchie d'annulation claire.
     */
    private suspend fun collectPortfolioWsUpdates() {
        getPortfolioWsUpdatesUseCase().collect {
            Timber.d("DashboardViewModel: portfolio_update received via WS — refreshing NAV/PnL")
            val portfolioId = _uiState.value.portfolioId
            val period = _uiState.value.selectedPeriod
            viewModelScope.launch { fetchNav(portfolioId) }
            viewModelScope.launch { fetchPnl(portfolioId, period) }
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
        val period = _uiState.value.selectedPeriod
        viewModelScope.launch {
            launch { fetchNav(portfolioId) }
            launch { fetchPnl(portfolioId, period) }
            launch { fetchQuote(DEFAULT_QUOTE_SYMBOL) }
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
     * Fetches a single quote. Three distinct error cases (CLAUDE.md §2 Dashboard polling pattern):
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
