package com.tradingplatform.app.ui.screens.alerts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradingplatform.app.domain.model.Alert
import com.tradingplatform.app.domain.model.AlertType
import com.tradingplatform.app.ui.components.EmptyAlertsIllustration
import com.tradingplatform.app.ui.components.EmptyState
import com.tradingplatform.app.ui.components.SkeletonAlertCard
import com.tradingplatform.app.ui.components.StatusBadge
import com.tradingplatform.app.ui.components.TradingCard
import com.tradingplatform.app.ui.components.rememberHapticFeedback
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Écran Alertes : sélecteur à deux segments en tête d'écran.
 *
 * - « Cet appareil » : alertes FCM persistées en local (Room) — fonctionne hors ligne.
 * - « Serveur » : boîte de réception du serveur, EN LIGNE UNIQUEMENT (voir [InboxSection]).
 *
 * Segment « Cet appareil » :
 * - Skeleton loading, état vide illustré.
 * - Une alerte non lue n'est signalée qu'une fois : par la pastille de sa carte (pas de bandeau
 *   de comptage, pas de liseré ni de surélévation en plus).
 * - Les chips de filtre « techniques » ne sont proposées qu'aux comptes admin.
 * - Haptique sur le swipe « marquer comme lu ».
 *
 * « Tout lire » (barre du haut) agit sur le segment affiché et n'apparaît que s'il y a des
 * non-lues dans ce segment.
 *
 * @param onOpenSettings ouvre l'écran Réglages (icône de la barre du haut) — câblé par la
 *                       navigation.
 * @param onServerUnreadChange appelé avec le décompte des notifications serveur non lues chaque
 *                       fois qu'il est (re)lu avec succès ou modifié par une lecture — jamais avec
 *                       la valeur initiale avant la première lecture. À brancher sur le badge de
 *                       l'onglet Alertes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertListScreen(
    modifier: Modifier = Modifier,
    viewModel: AlertsViewModel = hiltViewModel(),
    onOpenSettings: () -> Unit = {},
    onServerUnreadChange: (Int) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedTypes by viewModel.selectedTypes.collectAsStateWithLifecycle()
    val availableTypes by viewModel.availableTypes.collectAsStateWithLifecycle()
    val segment by viewModel.selectedSegment.collectAsStateWithLifecycle()
    val inboxState by viewModel.inboxState.collectAsStateWithLifecycle()
    val isInboxRefreshing by viewModel.isInboxRefreshing.collectAsStateWithLifecycle()
    val serverUnread by viewModel.serverUnreadCount.collectAsStateWithLifecycle()
    val serverUnreadKnown by viewModel.isServerUnreadKnown.collectAsStateWithLifecycle()
    val haptic = rememberHapticFeedback()
    val currentOnServerUnreadChange by rememberUpdatedState(onServerUnreadChange)

    // Ouverture de l'écran : relit le décompte serveur (et recharge la boîte si « Serveur » est affiché).
    LaunchedEffect(viewModel) { viewModel.onScreenOpened() }

    // Le badge ne reçoit jamais la valeur initiale (0) avant la première lecture réussie.
    LaunchedEffect(serverUnread, serverUnreadKnown) {
        if (serverUnreadKnown) currentOnServerUnreadChange(serverUnread)
    }

    val isServerSegment = segment == AlertsSegment.SERVER
    // Segment « Serveur » : le décompte serveur peut dépasser les lignes chargées (50 max).
    val unreadCount = if (isServerSegment) {
        (inboxState as? InboxUiState.Success)?.let { maxOf(it.unreadCount, serverUnread) } ?: 0
    } else {
        (uiState as? AlertsUiState.Success)?.unreadCount ?: 0
    }
    val unreadNoun = if (isServerSegment) "notification" else "alerte"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Alertes") },
                actions = {
                    if (unreadCount > 0) {
                        TextButton(
                            onClick = {
                                haptic.confirm()
                                if (isServerSegment) viewModel.markAllInboxRead() else viewModel.markAllAsRead()
                            },
                            modifier = Modifier.semantics {
                                contentDescription = "Tout marquer comme lu, " +
                                    "$unreadCount $unreadNoun${if (unreadCount > 1) "s" else ""} " +
                                    "non lue${if (unreadCount > 1) "s" else ""}"
                            },
                        ) {
                            Text(text = "Tout lire")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Réglages",
                        )
                    }
                },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            AlertsSegmentedTabs(
                selected = segment,
                onSelect = viewModel::selectSegment,
            )

            when (segment) {
                AlertsSegment.DEVICE -> DeviceAlertsSection(
                    uiState = uiState,
                    selectedTypes = selectedTypes,
                    availableTypes = availableTypes,
                    onToggleType = { type ->
                        val updated = if (type in selectedTypes) selectedTypes - type else selectedTypes + type
                        viewModel.setTypeFilter(updated)
                    },
                    onMarkAsRead = viewModel::markAsRead,
                )

                AlertsSegment.SERVER -> InboxSection(
                    state = inboxState,
                    isRefreshing = isInboxRefreshing,
                    onRefresh = viewModel::refreshInbox,
                    onMarkRead = viewModel::markInboxRead,
                )
            }
        }
    }
}

// ── Segment « Cet appareil » (alertes FCM locales) ────────────────────────────

/**
 * Filtres + liste des alertes FCM locales — comportement inchangé par rapport à l'écran
 * d'origine (le sélecteur de segments s'insère seulement au-dessus).
 */
@Composable
private fun DeviceAlertsSection(
    uiState: AlertsUiState,
    selectedTypes: Set<AlertType>,
    availableTypes: List<AlertType>,
    onToggleType: (AlertType) -> Unit,
    onMarkAsRead: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        // Filter bar — always visible between the segment selector and list content
        AlertFilterBar(
            selectedTypes = selectedTypes,
            onToggleType = onToggleType,
            availableTypes = availableTypes,
        )

        // Alerts are sourced exclusively from FCM → Room (no network endpoint).
        // The Room Flow updates reactively — no pull-to-refresh needed.
        when (uiState) {
            is AlertsUiState.Loading -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    items(6) {
                        SkeletonAlertCard()
                    }
                }
            }

            is AlertsUiState.Success -> {
                AlertListContent(
                    alerts = uiState.alerts,
                    onMarkAsRead = onMarkAsRead,
                )
            }

            is AlertsUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Spacing.lg),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        illustration = { EmptyAlertsIllustration() },
                        title = "Erreur de chargement",
                        message = uiState.message,
                    )
                }
            }
        }
    }
}

// ── Alert list content ─────────────────────────────────────────────────────────

@Composable
private fun AlertListContent(
    alerts: List<Alert>,
    onMarkAsRead: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (alerts.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.xxxl),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        illustration = { EmptyAlertsIllustration() },
                        title = "Aucune alerte",
                        message = "Votre système fonctionne normalement. Les alertes apparaîtront ici.",
                    )
                }
            }
        } else {
            items(
                items = alerts,
                key = { it.id },
            ) { alert ->
                SwipeToMarkReadAlert(
                    alert = alert,
                    onMarkAsRead = onMarkAsRead,
                )
            }
        }
    }
}

// ── Swipe-to-dismiss wrapper ───────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToMarkReadAlert(
    alert: Alert,
    onMarkAsRead: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismissState = rememberSwipeToDismissBoxState()
    val haptic = rememberHapticFeedback()

    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.StartToEnd ||
            dismissState.currentValue == SwipeToDismissBoxValue.EndToStart
        ) {
            if (!alert.read) {
                haptic.confirm()
                onMarkAsRead(alert.id)
            }
            dismissState.reset()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        backgroundContent = {
            val extendedColors = LocalExtendedColors.current
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        color = extendedColors.infoContainer,
                        shape = MaterialTheme.shapes.medium,
                    ),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = "Marquer comme lu",
                    style = MaterialTheme.typography.labelMedium,
                    color = extendedColors.onInfoContainer,
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
            }
        },
    ) {
        AlertCard(
            alert = alert,
            onTap = { if (!alert.read) onMarkAsRead(alert.id) },
        )
    }
}

// ── Alert card ─────────────────────────────────────────────────────────────────

@Composable
private fun AlertCard(
    alert: Alert,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accentColor = alertTypeColor(alert.type)

    val a11yDescription = buildString {
        append("Alerte ")
        if (!alert.read) append("non lue, ")
        append(alert.title)
        append(", type ")
        append(alertTypeLabel(alert.type))
        append(", reçue ")
        append(formatTimestampVerbose(alert.receivedAt))
    }

    TradingCard(
        onClick = onTap,
        modifier = modifier.semantics { contentDescription = a11yDescription },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalAlignment = Alignment.Top,
        ) {
            // Unique signal « non lue » : la pastille. Le libellé TalkBack est porté par la carte
            // (a11yDescription) — la pastille est donc masquée aux services d'accessibilité.
            if (!alert.read) {
                Box(
                    modifier = Modifier
                        .padding(top = Spacing.xs)
                        .size(Spacing.sm)
                        .clip(CircleShape)
                        .background(accentColor)
                        .clearAndSetSemantics { },
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
            } else {
                // Réserve la même largeur pour aligner les titres lus / non lus.
                Spacer(modifier = Modifier.width(Spacing.sm + Spacing.sm))
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = alert.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    AlertTypeBadge(type = alert.type)
                }

                Text(
                    text = alert.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = formatTimestamp(alert.receivedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── AlertType badge ────────────────────────────────────────────────────────────

@Composable
private fun AlertTypeBadge(
    type: AlertType,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current
    val (label, color) = when (type) {
        AlertType.PRICE_ALERT -> "Prix" to extendedColors.info
        AlertType.TRADE_EXECUTED -> "Trade" to extendedColors.success
        AlertType.DEVICE_OFFLINE -> "Device OFF" to extendedColors.statusOffline
        AlertType.DEVICE_ONLINE -> "Device ON" to extendedColors.statusOnline
        AlertType.DEVICE_UNPAIRED -> "Désappairé" to MaterialTheme.colorScheme.error
        AlertType.SCRAPING_ERROR -> "Scraping" to extendedColors.warning
        AlertType.OTA_COMPLETE -> "OTA" to extendedColors.success
        AlertType.SYSTEM_ERROR -> "Erreur" to MaterialTheme.colorScheme.error
        AlertType.PORTFOLIO_UPDATE -> "Portfolio" to extendedColors.warning
        AlertType.UNKNOWN -> "Info" to extendedColors.info
    }
    StatusBadge(
        text = label,
        color = color,
        modifier = modifier,
    )
}

@Composable
private fun alertTypeColor(type: AlertType): Color {
    val extendedColors = LocalExtendedColors.current
    return when (type) {
        AlertType.PRICE_ALERT -> extendedColors.info
        AlertType.TRADE_EXECUTED -> extendedColors.success
        AlertType.DEVICE_OFFLINE -> extendedColors.statusOffline
        AlertType.DEVICE_ONLINE -> extendedColors.statusOnline
        AlertType.DEVICE_UNPAIRED -> MaterialTheme.colorScheme.error
        AlertType.SCRAPING_ERROR -> extendedColors.warning
        AlertType.OTA_COMPLETE -> extendedColors.success
        AlertType.SYSTEM_ERROR -> MaterialTheme.colorScheme.error
        AlertType.PORTFOLIO_UPDATE -> extendedColors.warning
        AlertType.UNKNOWN -> extendedColors.info
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────────

private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm")

internal fun formatTimestamp(instant: Instant): String {
    val now = Instant.now()
    val minutesAgo = ChronoUnit.MINUTES.between(instant, now)
    return when {
        minutesAgo < 1 -> "À l'instant"
        minutesAgo < 60 -> "Il y a $minutesAgo min"
        else -> {
            val zoned = instant.atZone(ZoneId.systemDefault())
            val nowZoned = now.atZone(ZoneId.systemDefault())
            if (zoned.toLocalDate() == nowZoned.toLocalDate()) {
                TIME_FORMATTER.format(zoned)
            } else {
                DATE_TIME_FORMATTER.format(zoned)
            }
        }
    }
}

internal fun formatTimestampVerbose(instant: Instant): String {
    val zoned = instant.atZone(ZoneId.systemDefault())
    return "le ${DATE_TIME_FORMATTER.format(zoned)}"
}

internal fun alertTypeLabel(type: AlertType): String = when (type) {
    AlertType.PRICE_ALERT -> "alerte de prix"
    AlertType.TRADE_EXECUTED -> "trade exécuté"
    AlertType.DEVICE_OFFLINE -> "device hors ligne"
    AlertType.DEVICE_ONLINE -> "device en ligne"
    AlertType.DEVICE_UNPAIRED -> "device désappairé"
    AlertType.SCRAPING_ERROR -> "erreur de scraping"
    AlertType.OTA_COMPLETE -> "mise à jour OTA terminée"
    AlertType.SYSTEM_ERROR -> "erreur système"
    AlertType.PORTFOLIO_UPDATE -> "mise à jour de portfolio"
    AlertType.UNKNOWN -> "information"
}
