package com.tradingplatform.app.ui.screens.market

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.model.Quote
import com.tradingplatform.app.domain.model.SymbolInfo
import com.tradingplatform.app.domain.usecase.market.AddToWatchlistUseCase
import com.tradingplatform.app.domain.usecase.market.GetAvailableSymbolsUseCase
import com.tradingplatform.app.domain.usecase.market.GetPublicWsConnectionStateUseCase
import com.tradingplatform.app.domain.usecase.market.GetQuoteStreamUseCase
import com.tradingplatform.app.domain.usecase.market.GetQuoteUseCase
import com.tradingplatform.app.domain.usecase.market.GetSymbolHistoryUseCase
import com.tradingplatform.app.domain.usecase.market.GetWatchlistUseCase
import com.tradingplatform.app.domain.usecase.market.RemoveFromWatchlistUseCase
import com.tradingplatform.app.ui.common.QuoteFallbackController
import com.tradingplatform.app.vpn.VpnNotConnectedException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        /**
         * Symbols whose last fetch failed (VPN down, WS/REST timeout).
         * The cached quote is still displayed — this set lets the UI flag it as stale.
         */
        val staleSymbols: Set<String> = emptySet(),
    ) : MarketDataUiState
    data class Error(val message: String) : MarketDataUiState
}

sealed interface SymbolPickerUiState {
    data object Idle : SymbolPickerUiState
    data object Loading : SymbolPickerUiState
    data class Success(
        val symbols: List<SymbolInfo>,
        val hasMore: Boolean = false,
        val isLoadingMore: Boolean = false,
        val nextOffset: Int = 0,
    ) : SymbolPickerUiState
    data class Error(val message: String) : SymbolPickerUiState
}

// ── Constants ────────────────────────────────────────────────────────────────

private const val SYMBOL_SEARCH_DEBOUNCE_MS = 300L
private const val SYMBOLS_PAGE_SIZE = 50
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
    getPublicWsConnectionStateUseCase: GetPublicWsConnectionStateUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<MarketDataUiState>(MarketDataUiState.Loading)
    val uiState: StateFlow<MarketDataUiState> = _uiState.asStateFlow()

    private val _symbolPickerState = MutableStateFlow<SymbolPickerUiState>(SymbolPickerUiState.Idle)
    val symbolPickerState: StateFlow<SymbolPickerUiState> = _symbolPickerState.asStateFlow()

    /** Current quotes map — updated by WS or polling. */
    private val quotes = mutableMapOf<String, Quote>()

    /** Sparkline history data per symbol (close prices). */
    private val sparklines = mutableMapOf<String, List<BigDecimal>>()

    /**
     * Active quote watch jobs per symbol (WS stream + state-driven REST fallback).
     * Cancelling a job unsubscribes the symbol (ref-counted in the WS client) and stops polling.
     */
    private val watchJobs = mutableMapOf<String, Job>()

    /**
     * Symbols whose quote is no longer live (WS public down, or REST fallback failing) —
     * surfaced in [MarketDataUiState.Success.staleSymbols].
     */
    private val staleSymbols = mutableSetOf<String>()

    /**
     * WS stream + REST fallback driven by the public WS connection state (audit #13/#14):
     * polls every 30 s only while the WS is not Connected and the app is in foreground.
     */
    private val quoteFallback = QuoteFallbackController(
        scope = viewModelScope,
        connectionState = getPublicWsConnectionStateUseCase(),
        isForeground = getPublicWsConnectionStateUseCase.isAppForeground(),
        stream = { symbol -> getQuoteStreamUseCase(symbol) },
        fetch = { symbol -> getQuoteUseCase(symbol) },
        onQuote = ::onQuoteReceived,
        onStale = ::markStale,
        onFetchError = ::onQuoteFetchError,
    )

    /** Debounced search-query job for the symbol picker — cancelled/relaunched on each keystroke. */
    private var symbolSearchJob: Job? = null

    /** "Load more" pagination job for the symbol picker. */
    private var loadMoreSymbolsJob: Job? = null

    /** Search term of the last symbol picker request — reused by [refreshSymbols] and [loadMoreSymbols]. */
    private var currentSymbolSearch: String? = null

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

    /**
     * (Re)loads the first page of the symbol picker using [currentSymbolSearch] — called both
     * when the picker is first opened (search still unset) and from its "Réessayer" retry.
     */
    fun refreshSymbols() {
        symbolSearchJob?.cancel()
        loadMoreSymbolsJob?.cancel()
        viewModelScope.launch {
            loadSymbols(search = currentSymbolSearch, offset = 0)
        }
    }

    /**
     * Server-side search — debounced 300 ms so each keystroke doesn't fire a request.
     * Cancelling and relaunching the job on every call means only the last query in a
     * burst survives long enough to fire.
     */
    fun onSymbolSearchQueryChanged(query: String) {
        symbolSearchJob?.cancel()
        symbolSearchJob = viewModelScope.launch {
            delay(SYMBOL_SEARCH_DEBOUNCE_MS)
            loadSymbols(search = query.trim().ifBlank { null }, offset = 0)
        }
    }

    /** Fetches the next page (offset from the current [SymbolPickerUiState.Success]) and appends it. */
    fun loadMoreSymbols() {
        val current = _symbolPickerState.value
        if (current !is SymbolPickerUiState.Success || !current.hasMore || current.isLoadingMore) {
            return
        }
        _symbolPickerState.value = current.copy(isLoadingMore = true)
        loadMoreSymbolsJob?.cancel()
        loadMoreSymbolsJob = viewModelScope.launch {
            getAvailableSymbolsUseCase(
                search = currentSymbolSearch,
                limit = SYMBOLS_PAGE_SIZE,
                offset = current.nextOffset,
            )
                .onSuccess { page ->
                    val existing = (_symbolPickerState.value as? SymbolPickerUiState.Success)
                        ?.symbols
                        ?: current.symbols
                    _symbolPickerState.value = SymbolPickerUiState.Success(
                        symbols = existing + page.items,
                        hasMore = page.hasMore,
                        isLoadingMore = false,
                        nextOffset = page.nextOffset,
                    )
                }
                .onFailure { e ->
                    Timber.tag(TAG).w(e, "Failed to load more symbols")
                    val stateNow = _symbolPickerState.value
                    if (stateNow is SymbolPickerUiState.Success) {
                        _symbolPickerState.value = stateNow.copy(isLoadingMore = false)
                    }
                }
        }
    }

    private suspend fun loadSymbols(search: String?, offset: Int) {
        currentSymbolSearch = search
        _symbolPickerState.value = SymbolPickerUiState.Loading
        getAvailableSymbolsUseCase(search = search, limit = SYMBOLS_PAGE_SIZE, offset = offset)
            .onSuccess { page ->
                _symbolPickerState.value = SymbolPickerUiState.Success(
                    symbols = page.items,
                    hasMore = page.hasMore,
                    nextOffset = page.nextOffset,
                )
            }
            .onFailure { e ->
                _symbolPickerState.value =
                    SymbolPickerUiState.Error(e.localizedMessage ?: "Erreur")
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
     * Reconciles the active quote watches with the new watchlist.
     * Starts a watch for new symbols, cancels those no longer in the list (the WS client
     * ref-counts subscriptions, so the Dashboard keeps its own subscription alive).
     */
    private fun handleWatchlistUpdate(symbols: List<String>) {
        val currentSymbols = watchJobs.keys.toSet()
        val newSymbols = symbols.toSet()

        // Remove watches for symbols no longer in the watchlist
        (currentSymbols - newSymbols).forEach { symbol ->
            watchJobs.remove(symbol)?.cancel()
            quotes.remove(symbol)
            sparklines.remove(symbol)
            staleSymbols.remove(symbol)
        }

        // Watch new symbols (WS stream + state-driven REST fallback)
        (newSymbols - currentSymbols).forEach { symbol ->
            watchJobs[symbol] = quoteFallback.watch(symbol)
            fetchSparkline(symbol)
        }

        // Emit the new state
        _uiState.value = MarketDataUiState.Success(
            quotes = quotes.toMap(),
            watchlistSymbols = symbols,
            sparklines = sparklines.toMap(),
            staleSymbols = staleSymbols.toSet(),
        )
    }

    /** One-shot REST fetch (pull-to-refresh). Continuous polling is owned by [quoteFallback]. */
    private suspend fun fetchQuoteRest(symbol: String) {
        getQuoteUseCase(symbol)
            .onSuccess { quote -> onQuoteReceived(symbol, quote) }
            .onFailure { e -> onQuoteFetchError(symbol, e) }
    }

    /** New quote from the WS stream or the REST fallback — the symbol is live again. */
    private fun onQuoteReceived(symbol: String, quote: Quote) {
        quotes[symbol] = quote
        staleSymbols.remove(symbol)
        emitCurrentState()
    }

    /**
     * The public WS is no longer live (debounced) — keep the cached quote but flag it as stale
     * so the UI can show a "données du HH:mm" warning instead of silently stale prices.
     */
    private fun markStale(symbol: String) {
        if (quotes.containsKey(symbol) && staleSymbols.add(symbol)) {
            emitCurrentState()
        }
    }

    private fun onQuoteFetchError(symbol: String, e: Throwable) {
        when (e) {
            is VpnNotConnectedException,
            is SocketTimeoutException,
            is IOException -> markStale(symbol)
            else -> Timber.tag(TAG).w(e, "Quote fetch error for $symbol")
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
            staleSymbols = staleSymbols.toSet(),
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
