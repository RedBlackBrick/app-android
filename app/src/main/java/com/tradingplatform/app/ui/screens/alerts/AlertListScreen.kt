package com.tradingplatform.app.ui.screens.alerts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradingplatform.app.domain.model.Alert
import com.tradingplatform.app.domain.model.AlertType
import com.tradingplatform.app.ui.components.EmptyAlertsIllustration
import com.tradingplatform.app.ui.components.EmptyState
import com.tradingplatform.app.ui.components.SkeletonAlertCard
import com.tradingplatform.app.ui.components.StatusBadge
import com.tradingplatform.app.ui.components.rememberHapticFeedback
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Ecran de liste des alertes FCM persistées en local (Room).
 *
 * Improvements:
 * - Skeleton loading instead of opaque LoadingOverlay
 * - Illustrated empty state
 * - Colored left border on unread alerts by type
 * - Haptic feedback on swipe-to-mark-read
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertListScreen(
    modifier: Modifier = Modifier,
    viewModel: AlertsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedTypes by viewModel.selectedTypes.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Alertes") },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Filter bar — always visible between TopAppBar and list content
            AlertFilterBar(
                selectedTypes = selectedTypes,
                onToggleType = { type ->
                    val current = selectedTypes
                    val updated = if (type in current) current - type else current + type
                    viewModel.setTypeFilter(updated)
                },
            )

            // Alerts are sourced exclusively from FCM → Room (no network endpoint).
            // The Room Flow updates reactively — no pull-to-refresh needed.
            when (val state = uiState) {
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
                        alerts = state.alerts,
                        unreadCount = state.unreadCount,
                        onMarkAsRead = viewModel::markAsRead,
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
                            message = state.message,
                        )
                    }
                }
            }
        }
    }
}

// ── Alert list content ─────────────────────────────────────────────────────────

@Composable
private fun AlertListContent(
    alerts: List<Alert>,
    unreadCount: Int,
    onMarkAsRead: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (unreadCount > 0) {
            item {
                UnreadCountBanner(
                    unreadCount = unreadCount,
                    modifier = Modifier.padding(bottom = Spacing.xs),
                )
            }
        }

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

// ── Unread count banner ────────────────────────────────────────────────────────

@Composable
private fun UnreadCountBanner(
    unreadCount: Int,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "$unreadCount alerte${if (unreadCount > 1) "s" else ""} non lue${if (unreadCount > 1) "s" else ""}"
            },
        color = extendedColors.infoContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = "$unreadCount non lue${if (unreadCount > 1) "s" else ""}",
            style = MaterialTheme.typography.labelMedium,
            color = extendedColors.onInfoContainer,
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        )
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
    val extendedColors = LocalExtendedColors.current

    val cardColor = if (!alert.read) {
        extendedColors.cardSurfaceElevated
    } else {
        extendedColors.cardSurface
    }

    val accentColor = alertTypeColor(alert.type)

    val unreadA11yDescription = buildString {
        append("Alerte ")
        if (!alert.read) append("non lue, ")
        append(alert.title)
        append(", type ")
        append(alertTypeLabel(alert.type))
        append(", reçue ")
        append(formatTimestampVerbose(alert.receivedAt))
    }

    Card(
        onClick = onTap,
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = unreadA11yDescription },
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (!alert.read) Spacing.xs else 0.dp,
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            // Colored left accent border for unread alerts
            if (!alert.read) {
                Box(
                    modifier = Modifier
                        .width(Spacing.xs)
                        .fillMaxHeight()
                        .background(
                            color = accentColor,
                            shape = RoundedCornerShape(topStart = Spacing.md, bottomStart = Spacing.md),
                        ),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.lg),
                verticalAlignment = Alignment.Top,
            ) {
                if (!alert.read) {
                    Box(
                        modifier = Modifier
                            .padding(top = Spacing.xs)
                            .size(Spacing.sm)
                            .clip(CircleShape)
                            .background(accentColor)
                            .semantics {
                                contentDescription = "Alerte non lue"
                            },
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                } else {
                    Spacer(modifier = Modifier.width(Spacing.lg))
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
                        modifier = Modifier.semantics {
                            contentDescription = "Reçue ${formatTimestampVerbose(alert.receivedAt)}"
                        },
                    )
                }
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
