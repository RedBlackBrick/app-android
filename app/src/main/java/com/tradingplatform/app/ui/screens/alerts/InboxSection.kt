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
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.tradingplatform.app.domain.model.InboxNotification
import com.tradingplatform.app.ui.components.EmptyAlertsIllustration
import com.tradingplatform.app.ui.components.EmptyState
import com.tradingplatform.app.ui.components.SkeletonAlertCard
import com.tradingplatform.app.ui.components.TradingCard
import com.tradingplatform.app.ui.theme.IconSize
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing

private const val SKELETON_COUNT = 6

/** Lignes visibles d'un titre / d'un corps de notification tant que la carte n'est pas dépliée. */
private const val COLLAPSED_MAX_LINES = 2

/**
 * Segment « Serveur » de l'écran Alertes : boîte de réception du serveur (en ligne uniquement).
 *
 * - Toujours dans un `LazyColumn` (même pour les états vide / erreur / VPN) : le pull-to-refresh
 *   exige un enfant défilable.
 * - Une notification non lue n'est signalée qu'une fois : par la pastille de sa ligne (pas de
 *   comptage ni de gras en plus). Un tap sur la ligne la marque lue et déplie/replie son texte.
 * - Type inconnu : libellé « Notification » et icône neutre.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InboxSection(
    state: InboxUiState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onMarkRead: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            when (state) {
                is InboxUiState.Loading -> {
                    items(SKELETON_COUNT) {
                        SkeletonAlertCard()
                    }
                }

                is InboxUiState.Success -> {
                    if (state.items.isEmpty()) {
                        item {
                            InboxMessage(
                                illustration = { EmptyAlertsIllustration() },
                                title = "Aucune notification serveur",
                                message = "Les notifications de la plateforme (risque, stratégies, système) " +
                                    "apparaîtront ici.",
                            )
                        }
                    } else {
                        items(
                            items = state.items,
                            key = { it.id },
                        ) { notification ->
                            InboxCard(
                                item = notification,
                                onMarkRead = onMarkRead,
                            )
                        }
                    }
                }

                is InboxUiState.VpnRequired -> {
                    item {
                        InboxMessage(
                            illustration = {
                                StateIcon(
                                    imageVector = Icons.Filled.VpnKey,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            title = INBOX_VPN_REQUIRED_TITLE,
                            message = INBOX_VPN_REQUIRED_MESSAGE,
                            actionLabel = "Réessayer",
                            onAction = onRefresh,
                        )
                    }
                }

                is InboxUiState.Error -> {
                    item {
                        InboxMessage(
                            illustration = {
                                StateIcon(
                                    imageVector = Icons.Filled.Warning,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            title = "Erreur de chargement",
                            message = state.message,
                            actionLabel = "Réessayer",
                            onAction = onRefresh,
                        )
                    }
                }
            }
        }
    }
}

// ── États vide / erreur / VPN ─────────────────────────────────────────────────

@Composable
private fun InboxMessage(
    illustration: @Composable () -> Unit,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xxxl),
        contentAlignment = Alignment.Center,
    ) {
        EmptyState(
            illustration = illustration,
            title = title,
            message = message,
            actionLabel = actionLabel,
            onAction = onAction,
        )
    }
}

/** Icône décorative des états VPN / erreur (le titre porte le sens pour TalkBack). */
@Composable
private fun StateIcon(
    imageVector: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = imageVector,
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(IconSize.lg),
    )
}

// ── Ligne de notification ─────────────────────────────────────────────────────

@Composable
private fun InboxCard(
    item: InboxNotification,
    onMarkRead: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val category = inboxCategory(item.type)
    val title = inboxTitle(item)
    val typeLabel = inboxTypeLabel(item.type)
    val hasTimestamp = inboxHasTimestamp(item)
    val maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_MAX_LINES

    // Le texte complet est toujours lu par TalkBack, même si la carte est repliée.
    val a11yDescription = buildString {
        append("Notification ")
        if (!item.read) append("non lue, ")
        append(title)
        append(", type ")
        append(typeLabel.lowercase())
        if (hasTimestamp) {
            append(", reçue ")
            append(formatTimestampVerbose(item.createdAt))
        }
        if (item.body.isNotBlank()) {
            append(". ")
            append(item.body.trim())
        }
    }

    TradingCard(
        onClick = {
            expanded = !expanded
            if (!item.read) onMarkRead(item.id)
        },
        modifier = modifier.semantics { contentDescription = a11yDescription },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalAlignment = Alignment.Top,
        ) {
            // Unique signal « non lue » : la pastille (libellé TalkBack porté par la carte).
            if (!item.read) {
                Box(
                    modifier = Modifier
                        .padding(top = Spacing.xs)
                        .size(Spacing.sm)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clearAndSetSemantics { },
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
            } else {
                // Réserve la même largeur pour aligner les lignes lues / non lues.
                Spacer(modifier = Modifier.width(Spacing.sm + Spacing.sm))
            }

            Icon(
                imageVector = inboxIcon(category),
                contentDescription = null,
                tint = inboxAccentColor(category),
                modifier = Modifier.size(IconSize.md),
            )
            Spacer(modifier = Modifier.width(Spacing.md))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                )

                if (item.body.isNotBlank()) {
                    Text(
                        text = item.body.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = maxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Text(
                    text = if (hasTimestamp) {
                        "$typeLabel · ${formatTimestamp(item.createdAt)}"
                    } else {
                        typeLabel
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun inboxIcon(category: InboxCategory): ImageVector = when (category) {
    InboxCategory.RISK -> Icons.Filled.Warning
    InboxCategory.STRATEGY -> Icons.Filled.ShowChart
    InboxCategory.SYSTEM -> Icons.Filled.Cloud
    InboxCategory.GENERIC -> Icons.Filled.Notifications
}

@Composable
private fun inboxAccentColor(category: InboxCategory): Color {
    val extendedColors = LocalExtendedColors.current
    return when (category) {
        InboxCategory.RISK -> MaterialTheme.colorScheme.error
        InboxCategory.STRATEGY -> extendedColors.info
        InboxCategory.SYSTEM -> MaterialTheme.colorScheme.secondary
        InboxCategory.GENERIC -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
