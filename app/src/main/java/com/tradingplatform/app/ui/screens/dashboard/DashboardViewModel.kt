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
import com.tradingplatform.app.vpn.VpnNotConnectedException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

            // Start quote polling loop — while(isActive) never uses repeatOnLifecycle
            // (which is a Lifecycle extension, not available in ViewModel)
            while (isActive) {
                fetchQuote(DEFAULT_QUOTE_SYMBOL)
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
