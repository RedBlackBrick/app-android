package com.tradingplatform.app.ui.common

import com.tradingplatform.app.domain.model.Quote
import com.tradingplatform.app.domain.model.WsConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Cours temps réel via le WS public + fallback REST piloté par l'état de connexion
 * (audit #13/#14, plan-market-data §D).
 *
 * Remplace les copies « collect WS → catch(Exception) → polling REST » de
 * `MarketDataViewModel` et `DashboardViewModel` : le flux WS ne lève jamais sur perte de
 * connexion (il reste actif et reprend après la reconnexion automatique), donc ces `catch`
 * étaient du code mort et le fallback ne démarrait jamais.
 *
 * Pour chaque symbole surveillé via [watch] :
 * 1. Le flux WS ([stream]) est collecté en permanence — il n'est **jamais** annulé sur
 *    coupure, si bien que la resouscription après reconnexion est automatique (le client
 *    renvoie les subscriptions actives sur onOpen) et les cours WS reprennent seuls.
 * 2. [connectionState] est réduit à « live ? » (`== Connected`), dédoublonné puis
 *    debouncé : `Connected` est propagé immédiatement, une perte de connexion seulement
 *    après [disconnectDebounceMs] (évite de basculer en polling sur un simple flap).
 * 3. Tant que la connexion n'est pas live : [onStale] est appelé une fois (le dernier cours
 *    affiché n'est plus temps réel), puis — uniquement si l'app est au premier plan
 *    ([isForeground]) — [fetch] est appelé toutes les [pollIntervalMs]. `collectLatest`
 *    annule la boucle dès que la connexion redevient `Connected` ou que l'app passe en
 *    arrière-plan.
 *
 * Tous les callbacks sont invoqués dans [scope] (typiquement `viewModelScope`, donc sur
 * Main) — ils peuvent muter l'état du ViewModel sans synchronisation supplémentaire.
 *
 * @param scope Scope propriétaire (`viewModelScope`) — son annulation arrête tout.
 * @param connectionState État de la connexion WS publique (GetPublicWsConnectionStateUseCase).
 * @param isForeground Premier plan de l'app ; `null` = considéré toujours au premier plan.
 * @param stream Flux WS d'un symbole (GetQuoteStreamUseCase).
 * @param fetch Lecture REST ponctuelle d'un symbole (GetQuoteUseCase).
 * @param onQuote Nouveau cours reçu (WS ou REST).
 * @param onStale La connexion WS n'est plus live — marquer le dernier cours comme périmé.
 * @param onFetchError Échec d'un [fetch] (hors annulation) — gestion propre à chaque écran.
 * @param pollIntervalMs Période du polling REST de secours (30 s, CLAUDE.md §2).
 * @param disconnectDebounceMs Délai avant de considérer une perte de connexion comme durable.
 */
class QuoteFallbackController(
    private val scope: CoroutineScope,
    private val connectionState: Flow<WsConnectionState>,
    private val isForeground: Flow<Boolean>?,
    private val stream: (String) -> Flow<Quote>,
    private val fetch: suspend (String) -> Result<Quote>,
    private val onQuote: (symbol: String, quote: Quote) -> Unit,
    private val onStale: (symbol: String) -> Unit,
    private val onFetchError: (symbol: String, error: Throwable) -> Unit = { _, _ -> },
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val disconnectDebounceMs: Long = DEFAULT_DISCONNECT_DEBOUNCE_MS,
) {

    /**
     * Démarre la surveillance de [symbol]. Annuler le [Job] retourné arrête le flux WS
     * (→ unsubscribe ref-compté côté client) et tout polling REST en cours.
     */
    fun watch(symbol: String): Job = scope.launch {
        // 1. Flux WS — collecté pour toute la durée du watch.
        launch {
            stream(symbol)
                .catch { e ->
                    // Défensif : le flux WS public ne lève pas sur coupure réseau. Une erreur
                    // ici est inattendue ; le fallback REST (piloté par l'état) prend le relais.
                    Timber.tag(TAG).w(e, "Quote stream for $symbol terminated unexpectedly")
                }
                .collect { quote -> onQuote(symbol, quote) }
        }

        // 2. Fallback REST piloté par l'état de connexion + premier plan.
        launch {
            val live = connectionState
                .map { it == WsConnectionState.Connected }
                .distinctUntilChanged()
                .debounceDisconnect()
            val foreground = isForeground ?: flowOf(true)

            combine(live, foreground) { isLive, isFg -> FallbackMode(isLive, isFg) }
                .distinctUntilChanged()
                .collectLatest { mode ->
                    if (mode.live) return@collectLatest
                    onStale(symbol)
                    if (!mode.foreground) return@collectLatest
                    Timber.tag(TAG).d("WS public not live — REST polling $symbol")
                    while (currentCoroutineContext().isActive) {
                        fetch(symbol)
                            .onSuccess { quote -> onQuote(symbol, quote) }
                            .onFailure { e ->
                                // Un repository runCatching{} peut encapsuler l'annulation.
                                if (e is CancellationException) throw e
                                onFetchError(symbol, e)
                            }
                        delay(pollIntervalMs)
                    }
                }
        }
    }

    /** `Connected` passe immédiatement ; une perte de connexion après [disconnectDebounceMs]. */
    @OptIn(FlowPreview::class)
    private fun Flow<Boolean>.debounceDisconnect(): Flow<Boolean> =
        debounce { isLive -> if (isLive) 0L else disconnectDebounceMs }

    private data class FallbackMode(val live: Boolean, val foreground: Boolean)

    companion object {
        private const val TAG = "QuoteFallbackController"

        /** Polling REST de secours — 30 s (CLAUDE.md §2 « Données de marché »). */
        const val DEFAULT_POLL_INTERVAL_MS = 30_000L

        /** Anti-flicker : une coupure < 2 s ne déclenche ni Stale ni polling. */
        const val DEFAULT_DISCONNECT_DEBOUNCE_MS = 2_000L
    }
}
