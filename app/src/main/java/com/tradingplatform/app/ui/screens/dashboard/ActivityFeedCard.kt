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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.tradingplatform.app.domain.model.ActivityItem
import com.tradingplatform.app.ui.components.TradingCard
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing

/**
 * Carte « Activité » du Dashboard : les [DASHBOARD_ACTIVITY_LIMIT] événements les plus récents
 * du flux temps réel, avec un lien « Tout voir » ([onSeeAll]) vers l'écran des alertes.
 *
 * Le ViewModel conserve jusqu'à 12 éléments ; c'est ici (via [recentActivity]) que la liste est
 * réduite pour l'affichage. Chaque ligne est un seul nœud TalkBack (description + heure).
 */
@Composable
fun ActivityFeedCard(
    items: List<ActivityItem>,
    isLive: Boolean,
    modifier: Modifier = Modifier,
    onSeeAll: (() -> Unit)? = null,
) {
    val extendedColors = LocalExtendedColors.current
    val visibleItems = remember(items) { recentActivity(items) }

    TradingCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(
                start = Spacing.lg,
                top = Spacing.sm,
                end = Spacing.sm,
                bottom = Spacing.md,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            // ── En-tête : titre (+ badge direct) et lien « Tout voir » ──────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Activité",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (isLive) {
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clearAndSetSemantics {
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (onSeeAll != null) {
                    TextButton(onClick = onSeeAll) {
                        Text(text = "Tout voir")
                    }
                }
            }

            // ── Éléments ou état vide ───────────────────────────────────────────
            Column(modifier = Modifier.padding(end = Spacing.sm)) {
                if (visibleItems.isEmpty()) {
                    Text(
                        text = "Aucune activité récente",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = Spacing.sm),
                    )
                } else {
                    visibleItems.forEachIndexed { index, item ->
                        ActivityItemRow(item = item)
                        if (index < visibleItems.lastIndex) {
                            HorizontalDivider(color = extendedColors.divider)
                        }
                    }
                }
            }
        }
    }
}

// ── Ligne individuelle ──────────────────────────────────────────────────────

@Composable
private fun ActivityItemRow(
    item: ActivityItem,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current
    val model = remember(item) { activityRowModel(item) }
    val dotColor: Color = when (model.dot) {
        ActivityDot.SUCCESS -> extendedColors.success
        ActivityDot.PRIMARY -> MaterialTheme.colorScheme.primary
        ActivityDot.WARNING -> extendedColors.warning
        ActivityDot.OFFLINE -> extendedColors.statusOffline
        ActivityDot.INFO -> extendedColors.info
        ActivityDot.TERTIARY -> MaterialTheme.colorScheme.tertiary
    }
    val relativeTime = remember(item.timestamp) { formatRelativeTime(item.timestamp) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs)
            // Un seul nœud TalkBack par ligne (sinon description + textes lus deux fois).
            .clearAndSetSemantics { contentDescription = "${model.description}, $relativeTime" },
        verticalAlignment = Alignment.Top,
    ) {
        // Point coloré
        Box(
            modifier = Modifier
                .padding(top = Spacing.xs)
                .size(Spacing.sm)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(modifier = Modifier.width(Spacing.md))

        // Texte
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = model.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = model.subtext,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        Spacer(modifier = Modifier.width(Spacing.sm))

        // Temps relatif
        Text(
            text = relativeTime,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}
