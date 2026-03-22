package com.tradingplatform.app.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.tradingplatform.app.domain.model.ActivityItem
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing
import java.time.Duration
import java.time.Instant

/**
 * Card displaying the real-time activity feed on the Dashboard.
 *
 * Shows a live badge when the WebSocket is connected and a list of the most
 * recent activity items (max 12). Uses [LocalExtendedColors] for dot colors
 * and [Spacing] tokens for all dimensions.
 *
 * Each row is accessible via TalkBack with a descriptive [contentDescription].
 */
@Composable
fun ActivityFeedCard(
    items: List<ActivityItem>,
    isLive: Boolean,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = extendedColors.cardSurface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            // ── Header: title + live badge ──────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Activit\u00e9 en temps r\u00e9el",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (isLive) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.semantics {
                            contentDescription = "Flux en direct actif"
                        },
                    ) {
                        Box(
                            modifier = Modifier
                                .size(Spacing.sm)
                                .clip(CircleShape)
                                .background(extendedColors.statusOnline),
                        )
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        Text(
                            text = "En direct",
                            style = MaterialTheme.typography.labelSmall,
                            color = extendedColors.statusOnline,
                        )
                    }
                }
            }

            // ── Items or empty state ────────────────────────────────────────
            if (items.isEmpty()) {
                Text(
                    text = "Aucune activit\u00e9 r\u00e9cente",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(vertical = Spacing.md)
                        .semantics {
                            contentDescription = "Aucune activit\u00e9 r\u00e9cente dans le flux"
                        },
                )
            } else {
                items.forEachIndexed { index, item ->
                    ActivityItemRow(item = item)
                    if (index < items.lastIndex) {
                        HorizontalDivider(color = extendedColors.divider)
                    }
                }
            }
        }
    }
}

// ── Individual row ──────────────────────────────────────────────────────────

@Composable
private fun ActivityItemRow(
    item: ActivityItem,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current

    val dotColor: Color
    val label: String
    val subtext: String
    val description: String

    when (item) {
        is ActivityItem.OrderFilled -> {
            dotColor = extendedColors.success
            val sideLabel = when (item.side.lowercase()) {
                "buy" -> "Achat"
                "sell" -> "Vente"
                else -> item.side
            }
            val qtyText = item.quantity?.toString() ?: "?"
            label = "$sideLabel $qtyText \u00d7 ${item.symbol}"
            subtext = item.status
            description = "Ordre : $sideLabel de $qtyText ${item.symbol}, statut ${item.status}"
        }
        is ActivityItem.Signal -> {
            dotColor = MaterialTheme.colorScheme.primary // Indigo
            val pct = "%.0f".format(item.confidence * 100)
            label = "Signal : ${item.symbol} (${item.action.uppercase()} $pct%)"
            subtext = item.strategyType
            description = "Signal de strat\u00e9gie : ${item.action} ${item.symbol} avec confiance $pct pourcent"
        }
        is ActivityItem.RiskAlert -> {
            dotColor = when (item.severity.lowercase()) {
                "warning" -> extendedColors.warning
                "error", "critical" -> extendedColors.statusOffline
                else -> extendedColors.warning
            }
            label = "Alerte : ${item.title}"
            subtext = item.body
            description = "Alerte ${item.severity} : ${item.title}. ${item.body}"
        }
        is ActivityItem.PortfolioChange -> {
            dotColor = extendedColors.info
            val parts = mutableListOf<String>()
            if (item.nav != null) parts.add("NAV %.2f".format(item.nav))
            if (item.dailyPnl != null) parts.add("P&L jour %+.2f".format(item.dailyPnl))
            label = if (parts.isNotEmpty()) "Portfolio : ${parts.joinToString(" | ")}" else "Portfolio mis \u00e0 jour"
            subtext = "Mise \u00e0 jour portfolio"
            description = "Mise \u00e0 jour portfolio : ${parts.joinToString(", ").ifEmpty { "valeurs mises \u00e0 jour" }}"
        }
    }

    val relativeTime = remember(item.timestamp) { formatRelativeTime(item.timestamp) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs)
            .semantics { contentDescription = "$description, $relativeTime" },
        verticalAlignment = Alignment.Top,
    ) {
        // Colored dot
        Box(
            modifier = Modifier
                .padding(top = Spacing.xs)
                .size(Spacing.sm)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(modifier = Modifier.width(Spacing.md))

        // Text content
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = subtext,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        Spacer(modifier = Modifier.width(Spacing.sm))

        // Relative time
        Text(
            text = relativeTime,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}

// ── Time formatting ─────────────────────────────────────────────────────────

/**
 * Formats an [Instant] as a French-locale relative time string.
 * Examples: "maintenant", "il y a 2m", "il y a 1h".
 */
private fun formatRelativeTime(timestamp: Instant): String {
    val seconds = Duration.between(timestamp, Instant.now()).seconds
    return when {
        seconds < 10 -> "maintenant"
        seconds < 60 -> "il y a ${seconds}s"
        seconds < 3600 -> "il y a ${seconds / 60}m"
        seconds < 86400 -> "il y a ${seconds / 3600}h"
        else -> "il y a ${seconds / 86400}j"
    }
}
