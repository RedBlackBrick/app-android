package com.tradingplatform.app.ui.screens.market

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.model.Quote
import com.tradingplatform.app.domain.usecase.market.AddToWatchlistUseCase
import com.tradingplatform.app.domain.usecase.market.GetAvailableSymbolsUseCase
import com.tradingplatform.app.domain.usecase.market.GetQuoteStreamUseCase
import com.tradingplatform.app.domain.usecase.market.GetQuoteUseCase
import com.tradingplatform.app.domain.usecase.market.GetSymbolHistoryUseCase
import com.tradingplatform.app.domain.usecase.market.GetWatchlistUseCase
import com.tradingplatform.app.domain.usecase.market.RemoveFromWatchlistUseCase
import com.tradingplatform.app.vpn.VpnNotConnectedException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import java.math.BigDecimal
import java.net.SocketTimeoutException
import javax.inject.Inject

// ── UiState ──────────────────────────────────────────────────────────────────

sealed interface MarketDataUiState {
    data object Loading : MarketDataUiState
    data class Success(
        val quotes: Map<String, Quote>,
        val watchlistSymbols: List<String>,
        val sparklines: Map<String, List<BigDecimal>> = emptyMap(),
    ) : MarketDataUiState
    data class Error(val message: String) : MarketDataUiState
}

sealed interface SymbolPickerUiState {
    data object Idle : SymbolPickerUiState
    data object Loading : SymbolPickerUiState
    data class Success(val symbols: List<String>) : SymbolPickerUiState
    data class Error(val message: String) : SymbolPickerUiState
}

// ── Constants ────────────────────────────────────────────────────────────────

private const val QUOTE_POLL_INTERVAL_MS = 30_000L
private const val TAG = "MarketDataViewModel"

@HiltViewModel
class MarketDataViewModel @Inject constructor(
    private val getWatchlistUseCase: GetWatchlistUseCase,
    private val getQuoteStreamUseCase: GetQuoteStreamUseCase,
    private val getQuoteUseCase: GetQuoteUseCase,
    private val addToWatchlistUseCase: AddToWatchlistUseCase,
    private val removeFromWatchlistUseCase: RemoveFromWatchlistUseCase,
    private val getAvailableSymbolsUseCase: GetAvailableSymbolsUseCase,
    private val getSymbolHistoryUseCase: GetSymbolHistoryUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<MarketDataUiState>(MarketDataUiState.Loading)
    val uiState: StateFlow<MarketDataUiState> = _uiState.asStateFlow()

    private val _symbolPickerState = MutableStateFlow<SymbolPickerUiState>(SymbolPickerUiState.Idle)
    val symbolPickerState: StateFlow<SymbolPickerUiState> = _symbolPickerState.asStateFlow()

    /** Current quotes map — updated by WS or polling. */
    private val quotes = mutableMapOf<String, Quote>()

    /** Sparkline history data per symbol (close prices). */
    private val sparklines = mutableMapOf<String, List<BigDecimal>>()

    /** Active WS subscription jobs per symbol. */
    private val wsJobs = mutableMapOf<String, Job>()

    /** Active REST polling fallback jobs per symbol. */
    private val pollingJobs = mutableMapOf<String, Job>()

    init {
        viewModelScope.launch {
            getWatchlistUseCase().collect { symbols ->
                handleWatchlistUpdate(symbols)
            }
        }
    }

    // ── Public actions ──────────────────────────────────────────────────────

    fun addSymbol(symbol: String) {
        viewModelScope.launch {
            addToWatchlistUseCase(symbol)
                .onFailure { e ->
                    Timber.tag(TAG).w(e, "Failed to add symbol to watchlist: $symbol")
                }
        }
    }

    fun removeSymbol(symbol: String) {
        viewModelScope.launch {
            removeFromWatchlistUseCase(symbol)
                .onFailure { e ->
                    Timber.tag(TAG).w(e, "Failed to remove symbol from watchlist: $symbol")
                }
        }
    }

    fun refreshSymbols() {
        _symbolPickerState.value = SymbolPickerUiState.Loading
        viewModelScope.launch {
            getAvailableSymbolsUseCase()
                .onSuccess { symbols ->
                    _symbolPickerState.value = SymbolPickerUiState.Success(symbols)
                }
                .onFailure { e ->
                    _symbolPickerState.value =
                        SymbolPickerUiState.Error(e.localizedMessage ?: "Erreur")
                }
        }
    }

    fun refresh() {
        val currentState = _uiState.value
        val symbols = when (currentState) {
            is MarketDataUiState.Success -> currentState.watchlistSymbols
            else -> return
        }
        viewModelScope.launch {
            symbols.forEach { symbol ->
                launch { fetchQuoteRest(symbol) }
            }
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    /**
     * Reconciles the active subscriptions with the new watchlist.
     * Adds subscriptions for new symbols, removes those no longer in the list.
     */
    private fun handleWatchlistUpdate(symbols: List<String>) {
        val currentSymbols = wsJobs.keys.toSet() + pollingJobs.keys.toSet()
        val newSymbols = symbols.toSet()

        // Remove subscriptions for symbols no longer in the watchlist
        (currentSymbols - newSymbols).forEach { symbol ->
            wsJobs.remove(symbol)?.cancel()
            pollingJobs.remove(symbol)?.cancel()
            quotes.remove(symbol)
            sparklines.remove(symbol)
        }

        // Add subscriptions for new symbols
        (newSymbols - currentSymbols).forEach { symbol ->
            startWsSubscription(symbol)
            fetchSparkline(symbol)
        }

        // Emit the new state
        _uiState.value = MarketDataUiState.Success(
            quotes = quotes.toMap(),
            watchlistSymbols = symbols,
            sparklines = sparklines.toMap(),
        )
    }

    /**
     * Starts a WebSocket subscription for real-time quote updates.
     * Falls back to REST polling if the WS connection fails.
     */
    private fun startWsSubscription(symbol: String) {
        wsJobs[symbol]?.cancel()
        pollingJobs[symbol]?.cancel()
        pollingJobs.remove(symbol)

        wsJobs[symbol] = viewModelScope.launch {
            try {
                getQuoteStreamUseCase(symbol).collect { quote ->
                    quotes[symbol] = quote
                    emitCurrentState()
                }
            } catch (e: VpnNotConnectedException) {
                Timber.tag(TAG).w("VPN not connected for $symbol — falling back to REST")
                startPollingFallback(symbol)
            } catch (e: SocketTimeoutException) {
                Timber.tag(TAG).w("WS timeout for $symbol — falling back to REST")
                startPollingFallback(symbol)
            } catch (e: IOException) {
                Timber.tag(TAG).w(e, "WS IO error for $symbol — falling back to REST")
                startPollingFallback(symbol)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Unexpected WS error for $symbol — falling back to REST")
                startPollingFallback(symbol)
            }
        }
    }

    /**
     * REST polling fallback — used when WS is unavailable.
     * Polls every 30 seconds per CLAUDE.md market data strategy.
     */
    private fun startPollingFallback(symbol: String) {
        pollingJobs[symbol]?.cancel()
        pollingJobs[symbol] = viewModelScope.launch {
            while (isActive) {
                fetchQuoteRest(symbol)
                delay(QUOTE_POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun fetchQuoteRest(symbol: String) {
        getQuoteUseCase(symbol)
            .onSuccess { quote ->
                quotes[symbol] = quote
                emitCurrentState()
            }
            .onFailure { e ->
                when (e) {
                    is VpnNotConnectedException,
                    is SocketTimeoutException,
                    is IOException -> {
                        // Transient — keep previous state
                        Unit
                    }
                    else -> {
                        Timber.tag(TAG).w(e, "Quote fetch error for $symbol")
                    }
                }
            }
    }

    private fun emitCurrentState() {
        val current = _uiState.value
        val symbols = when (current) {
            is MarketDataUiState.Success -> current.watchlistSymbols
            else -> return
        }
        _uiState.value = MarketDataUiState.Success(
            quotes = quotes.toMap(),
            watchlistSymbols = symbols,
            sparklines = sparklines.toMap(),
        )
    }

    /**
     * Fetches sparkline history (30 close prices) for a symbol.
     * Runs asynchronously — the UI updates when data arrives.
     */
    private fun fetchSparkline(symbol: String) {
        viewModelScope.launch {
            getSymbolHistoryUseCase(symbol)
                .onSuccess { points ->
                    sparklines[symbol] = points
                    emitCurrentState()
                }
                .onFailure { e ->
                    Timber.tag(TAG).w(e, "Sparkline fetch failed for $symbol")
                }
        }
    }
}
