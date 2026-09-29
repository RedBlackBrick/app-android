package com.tradingplatform.app.ui.screens.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingplatform.app.domain.model.Alert
import com.tradingplatform.app.domain.model.AlertType
import com.tradingplatform.app.domain.model.InboxNotification
import com.tradingplatform.app.domain.usecase.alerts.GetAlertsUseCase
import com.tradingplatform.app.domain.usecase.alerts.GetFilteredAlertsUseCase
import com.tradingplatform.app.domain.usecase.alerts.GetInboxUnreadCountUseCase
import com.tradingplatform.app.domain.usecase.alerts.GetInboxUseCase
import com.tradingplatform.app.domain.usecase.alerts.MarkAlertReadUseCase
import com.tradingplatform.app.domain.usecase.alerts.MarkAllAlertsReadUseCase
import com.tradingplatform.app.domain.usecase.alerts.MarkAllInboxReadUseCase
import com.tradingplatform.app.domain.usecase.alerts.MarkInboxReadUseCase
import com.tradingplatform.app.domain.usecase.auth.GetAuthContextUseCase
import com.tradingplatform.app.domain.util.runCatchingCancellable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

// ── UiState ───────────────────────────────────────────────────────────────────

sealed interface AlertsUiState {
    data object Loading : AlertsUiState
    data class Success(
        val alerts: List<Alert>,
        val unreadCount: Int,
        val activeFilter: Set<AlertType>,
    ) : AlertsUiState
    data class Error(val message: String) : AlertsUiState
}

// ── Segment « Serveur » (boîte de réception) ─────────────────────────────────

/** Les deux sources de l'écran Alertes (sélecteur en tête d'écran). */
enum class AlertsSegment(val label: String) {
    /** Alertes FCM persistées localement (Room) — fonctionne hors ligne. */
    DEVICE("Cet appareil"),

    /** Boîte de réception serveur (`GET /v1/notifications`) — EN LIGNE UNIQUEMENT, sans cache. */
    SERVER("Serveur"),
}

/**
 * État du segment « Serveur ». Pas de mode hors-ligne : un échec remplace la liste, il n'y a
 * jamais de données périmées affichées comme actuelles.
 */
sealed interface InboxUiState {
    data object Loading : InboxUiState

    data class Success(val items: List<InboxNotification>) : InboxUiState {
        /** Non lues parmi les [items] chargés (le compteur serveur global est distinct). */
        val unreadCount: Int get() = items.count { !it.read }
    }

    /** Tunnel VPN absent (`VpnNotConnectedException`) : « Nécessite le VPN ». */
    data object VpnRequired : InboxUiState

    data class Error(val message: String) : InboxUiState
}

// ── Types de filtre ───────────────────────────────────────────────────────────

/**
 * Types « techniques » (supervision de la flotte / du backend) : leurs chips de filtre ne sont
 * proposées qu'aux comptes admin. Les alertes de ces types restent listées (filtre vide) ; seul
 * le filtre dédié est masqué pour alléger l'écran d'un compte standard.
 * `DEVICE_UNPAIRED` reste visible : c'est un événement de sécurité concernant les appareils de
 * l'utilisateur (« Mes appareils » est ouvert à tout compte authentifié).
 */
internal val ADMIN_ONLY_ALERT_TYPES: Set<AlertType> = setOf(
    AlertType.DEVICE_OFFLINE,
    AlertType.DEVICE_ONLINE,
    AlertType.SCRAPING_ERROR,
    AlertType.OTA_COMPLETE,
    AlertType.SYSTEM_ERROR,
)

/** Types dont la chip de filtre est affichée, dans l'ordre de l'enum. */
internal fun filterableAlertTypes(isAdmin: Boolean): List<AlertType> =
    if (isAdmin) {
        AlertType.entries.toList()
    } else {
        AlertType.entries.filter { it !in ADMIN_ONLY_ALERT_TYPES }
    }

// ── ViewModel ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AlertsViewModel @Inject constructor(
    private val getAlertsUseCase: GetAlertsUseCase,
    private val getFilteredAlertsUseCase: GetFilteredAlertsUseCase,
    private val markAlertReadUseCase: MarkAlertReadUseCase,
    private val markAllAlertsReadUseCase: MarkAllAlertsReadUseCase,
    private val getAuthContextUseCase: GetAuthContextUseCase,
    private val getInboxUseCase: GetInboxUseCase,
    private val getInboxUnreadCountUseCase: GetInboxUnreadCountUseCase,
    private val markInboxReadUseCase: MarkInboxReadUseCase,
    private val markAllInboxReadUseCase: MarkAllInboxReadUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<AlertsUiState>(AlertsUiState.Loading)
    val uiState: StateFlow<AlertsUiState> = _uiState.asStateFlow()

    private val _selectedTypes = MutableStateFlow<Set<AlertType>>(emptySet())
    val selectedTypes: StateFlow<Set<AlertType>> = _selectedTypes.asStateFlow()

    /**
     * Types proposés par la barre de filtres. Non-admin tant que le contexte d'auth n'est pas
     * lu (ou s'il est illisible) : on n'affiche jamais les chips techniques par défaut.
     */
    private val _availableTypes = MutableStateFlow(filterableAlertTypes(isAdmin = false))
    val availableTypes: StateFlow<List<AlertType>> = _availableTypes.asStateFlow()

    // ── Segment « Serveur » ───────────────────────────────────────────────────

    private val _selectedSegment = MutableStateFlow(AlertsSegment.DEVICE)
    val selectedSegment: StateFlow<AlertsSegment> = _selectedSegment.asStateFlow()

    private val _inboxState = MutableStateFlow<InboxUiState>(InboxUiState.Loading)
    val inboxState: StateFlow<InboxUiState> = _inboxState.asStateFlow()

    /** `true` pendant un rechargement qui garde la liste affichée (pull-to-refresh, retour d'onglet). */
    private val _isInboxRefreshing = MutableStateFlow(false)
    val isInboxRefreshing: StateFlow<Boolean> = _isInboxRefreshing.asStateFlow()

    private val _serverUnreadCount = MutableStateFlow(0)

    /**
     * Nombre de notifications serveur non lues (dernier décompte connu ; 0 tant que [isServerUnreadKnown]
     * est faux). Relu à l'ouverture de l'écran ([onScreenOpened]) et après chaque lecture ; hors VPN il
     * garde sa dernière valeur (pas de cache : jamais persisté).
     */
    val serverUnreadCount: StateFlow<Int> = _serverUnreadCount.asStateFlow()

    private val _isServerUnreadKnown = MutableStateFlow(false)

    /** `true` dès que le décompte serveur a été lu avec succès au moins une fois par ce ViewModel. */
    val isServerUnreadKnown: StateFlow<Boolean> = _isServerUnreadKnown.asStateFlow()

    private var inboxJob: Job? = null

    /**
     * Ids marqués lus localement (lecture optimiste, ou confirmés par le serveur). Réappliqués à
     * toute liste rechargée : une relecture partie avant que le POST « lu » soit traité ne doit pas
     * faire réapparaître la pastille. Retirés en cas d'échec du POST.
     */
    private val locallyReadInboxIds = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            runCatchingCancellable { getAuthContextUseCase().isAdmin }
                .onSuccess { isAdmin -> _availableTypes.value = filterableAlertTypes(isAdmin) }
                .onFailure { e -> Timber.w(e, "Auth context unreadable — technical alert filters hidden") }
        }
        viewModelScope.launch {
            // Error handling lives INSIDE the flatMapLatest lambda: a terminal `.catch` on the
            // outer chain would complete the whole pipeline on the first Room error, and later
            // filter changes would never re-subscribe (audit C-alerts-corr-1). Here only the
            // inner (per-filter) flow ends in Error; `_selectedTypes` keeps driving the chain.
            _selectedTypes
                .flatMapLatest { types -> alertsStateFor(types) }
                .collect { state -> _uiState.value = state }
        }
    }

    /**
     * Builds the UiState flow for one filter selection. Transient upstream failures are
     * retried [MAX_RETRIES] times with exponential backoff before surfacing an Error.
     * [CancellationException] is never retried (flatMapLatest cancels the previous inner
     * flow on each filter change). The use case is invoked lazily inside `flow {}` so a
     * synchronous throw is caught too and each retry opens a fresh Room query.
     */
    private fun alertsStateFor(types: Set<AlertType>): Flow<AlertsUiState> =
        flow {
            emitAll(if (types.isEmpty()) getAlertsUseCase() else getFilteredAlertsUseCase(types))
        }
            .map<List<Alert>, AlertsUiState> { alerts ->
                AlertsUiState.Success(
                    alerts = alerts,
                    unreadCount = alerts.count { !it.read },
                    activeFilter = types,
                )
            }
            .retryWhen { cause, attempt ->
                if (cause is CancellationException || attempt >= MAX_RETRIES) {
                    false
                } else {
                    Timber.w(cause, "Alerts flow failed — retry ${attempt + 1}/$MAX_RETRIES")
                    delay(RETRY_BASE_DELAY_MS shl attempt.toInt())
                    true
                }
            }
            .catch { e ->
                emit(AlertsUiState.Error(e.localizedMessage ?: "Impossible de charger les alertes"))
            }

    /**
     * Updates the type filter. Pass an empty set to show all alerts (no filter).
     */
    fun setTypeFilter(types: Set<AlertType>) {
        _selectedTypes.value = types
    }

    /**
     * Marks the alert with [alertId] as read. Errors are logged but do not disrupt the UI —
     * a failed mark-as-read is non-critical.
     */
    fun markAsRead(alertId: Long) {
        viewModelScope.launch {
            markAlertReadUseCase(alertId)
                .onFailure { e ->
                    Timber.e(e, "markAsRead failed for alertId=$alertId")
                }
        }
    }

    /**
     * Marks every unread alert as read (« Tout lire »). Errors are logged only — same
     * non-critical policy as [markAsRead]; the Room flow re-emits the new read state.
     */
    fun markAllAsRead() {
        viewModelScope.launch {
            markAllAlertsReadUseCase()
                .onFailure { e ->
                    Timber.e(e, "markAllAsRead failed")
                }
        }
    }

    // ── Segment « Serveur » : actions ─────────────────────────────────────────

    /**
     * Choisit la source affichée. Ouvrir « Serveur » (re)charge la boîte : ce segment est en ligne
     * uniquement, il n'y a rien à afficher hors requête. « Cet appareil » n'est jamais touché.
     */
    fun selectSegment(segment: AlertsSegment) {
        if (_selectedSegment.value == segment) return
        _selectedSegment.value = segment
        if (segment == AlertsSegment.SERVER) loadInbox()
    }

    /**
     * À appeler à chaque ouverture de l'écran : relit le décompte serveur (badge) et, si le segment
     * « Serveur » est celui affiché, recharge la boîte. Sans effet réseau bloquant : un échec (VPN
     * absent…) garde le dernier décompte connu.
     */
    fun onScreenOpened() {
        if (_selectedSegment.value == AlertsSegment.SERVER) {
            loadInbox()
        } else {
            viewModelScope.launch { fetchServerUnreadCount() }
        }
    }

    /** Pull-to-refresh / « Réessayer » du segment « Serveur ». */
    fun refreshInbox() {
        loadInbox()
    }

    /**
     * Marque une notification serveur comme lue. Mise à jour locale IMMÉDIATE (optimiste) puis
     * appel serveur (POST idempotent, sans confirmation), enfin relecture du décompte. Si l'appel
     * échoue la ligne redevient non lue : l'état affiché ne ment pas sur ce que le serveur sait.
     * Sans effet si la ligne est absente ou déjà lue.
     */
    fun markInboxRead(id: String) {
        val current = _inboxState.value as? InboxUiState.Success ?: return
        val target = current.items.firstOrNull { it.id == id } ?: return
        if (target.read) return

        locallyReadInboxIds += id
        _inboxState.value = current.copy(
            items = current.items.map { if (it.id == id) it.copy(read = true) else it },
        )
        val decremented = _serverUnreadCount.value > 0
        if (decremented) _serverUnreadCount.update { it - 1 }

        viewModelScope.launch {
            markInboxReadUseCase(id)
                .onFailure { e ->
                    Timber.w(e, "markInboxRead failed — local read state reverted")
                    locallyReadInboxIds -= id
                    _inboxState.update { state ->
                        if (state is InboxUiState.Success) {
                            state.copy(items = state.items.map { if (it.id == id) it.copy(read = false) else it })
                        } else {
                            state
                        }
                    }
                    if (decremented) _serverUnreadCount.update { it + 1 }
                }
            fetchServerUnreadCount()
        }
    }

    /**
     * « Tout marquer comme lu » du segment « Serveur » : même principe que [markInboxRead]
     * (optimiste, retour arrière si l'appel échoue, relecture du décompte). Marque aussi côté
     * serveur les notifications au-delà des lignes chargées.
     */
    fun markAllInboxRead() {
        val current = _inboxState.value as? InboxUiState.Success ?: return
        val newlyRead = current.items.filter { !it.read }.map { it.id }.toSet()
        val countBefore = _serverUnreadCount.value

        locallyReadInboxIds += newlyRead
        _inboxState.value = current.copy(items = current.items.map { it.copy(read = true) })
        _serverUnreadCount.value = 0

        viewModelScope.launch {
            markAllInboxReadUseCase()
                .onFailure { e ->
                    Timber.w(e, "markAllInboxRead failed — local read state reverted")
                    locallyReadInboxIds -= newlyRead
                    _inboxState.update { state ->
                        if (state is InboxUiState.Success) {
                            state.copy(
                                items = state.items.map { if (it.id in newlyRead) it.copy(read = false) else it },
                            )
                        } else {
                            state
                        }
                    }
                    _serverUnreadCount.value = countBefore
                }
            fetchServerUnreadCount()
        }
    }

    /**
     * (Re)charge la boîte serveur. Si une liste est déjà affichée elle reste visible pendant le
     * rechargement ([isInboxRefreshing]) ; sinon [InboxUiState.Loading] (squelettes). Un échec
     * REMPLACE la liste par [InboxUiState.VpnRequired] / [InboxUiState.Error] : pas de mode
     * hors-ligne, donc jamais de liste périmée présentée comme actuelle.
     */
    private fun loadInbox() {
        inboxJob?.cancel()
        inboxJob = viewModelScope.launch {
            val hasContent = _inboxState.value is InboxUiState.Success
            _isInboxRefreshing.value = hasContent
            if (!hasContent) _inboxState.value = InboxUiState.Loading

            getInboxUseCase()
                .onSuccess { items -> _inboxState.value = InboxUiState.Success(withLocalReads(items)) }
                .onFailure { e ->
                    Timber.w(e, "Server inbox load failed")
                    _inboxState.value = inboxFailureState(e)
                }
            _isInboxRefreshing.value = false
            fetchServerUnreadCount()
        }
    }

    /** Réapplique les lectures locales à une liste fraîchement chargée (voir [locallyReadInboxIds]). */
    private fun withLocalReads(items: List<InboxNotification>): List<InboxNotification> =
        if (locallyReadInboxIds.isEmpty()) {
            items
        } else {
            items.map { if (!it.read && it.id in locallyReadInboxIds) it.copy(read = true) else it }
        }

    /** Relit le décompte serveur ; un échec (VPN absent, réseau) garde la dernière valeur connue. */
    private suspend fun fetchServerUnreadCount() {
        getInboxUnreadCountUseCase()
            .onSuccess { count ->
                _serverUnreadCount.value = count.coerceAtLeast(0)
                _isServerUnreadKnown.value = true
            }
            .onFailure { e -> Timber.d(e, "Server unread count unavailable — keeping last known value") }
    }

    companion object {
        /** Retries per filter subscription before surfacing [AlertsUiState.Error]. */
        const val MAX_RETRIES = 3L

        /** Backoff base: 500 ms, 1 s, 2 s. */
        const val RETRY_BASE_DELAY_MS = 500L
    }
}
