package com.tradingplatform.app.ui.screens.dashboard.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tradingplatform.app.data.local.db.CacheTtl
import com.tradingplatform.app.ui.components.CacheTimestamp
import com.tradingplatform.app.ui.components.ShimmerBox
import com.tradingplatform.app.ui.components.TradingCard
import com.tradingplatform.app.ui.screens.dashboard.PortfolioOverviewModel
import com.tradingplatform.app.ui.screens.dashboard.PortfolioRowModel
import com.tradingplatform.app.ui.screens.dashboard.PortfolioTotalModel
import com.tradingplatform.app.ui.theme.IconSize
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing
import com.tradingplatform.app.ui.theme.asNumeric

/**
 * Carte « Mes portefeuilles » : une ligne par portefeuille du compte (nom, valeur, P&L de la période
 * sélectionnée en couleur P&L + pourcentage), la ligne ACTIVE mise en évidence (fond + coche + mot
 * « Actif » : pas la seule couleur). Toucher une ligne la rend active ([onSelect]). Une ligne
 * « Total » suit uniquement si tous les portefeuilles ont la même devise.
 *
 * L'appelant n'affiche cette carte que pour un compte à ≥ 2 portefeuilles (cf.
 * `shouldShowPortfolioOverview`).
 *
 * @param model lignes à afficher ; `null` = premier chargement (squelette de [placeholderRows] lignes).
 * @param caption légende de la période (« P&L du jour »…).
 * @param staleSyncedAt non nul quand les lignes affichées sont périmées (échec du dernier refresh) :
 *   horodatage de leur synchro.
 */
@Composable
internal fun DashboardPortfoliosCard(
    model: PortfolioOverviewModel?,
    caption: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholderRows: Int = 2,
    staleSyncedAt: Long? = null,
) {
    TradingCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                Text(
                    text = "Mes portefeuilles",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (model == null) {
                repeat(placeholderRows) {
                    ShimmerBox(
                        width = 300.dp,
                        height = Spacing.xxxl,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                model.rows.forEach { row ->
                    PortfolioRow(row = row, onClick = { onSelect(row.portfolioId) })
                }
                model.total?.let { total ->
                    HorizontalDivider(color = LocalExtendedColors.current.divider)
                    TotalRow(total = total)
                }
                if (staleSyncedAt != null) {
                    CacheTimestamp(
                        syncedAt = staleSyncedAt,
                        ttlMs = CacheTtl.PNL_MS,
                        modifier = Modifier.padding(horizontal = Spacing.sm),
                    )
                }
            }
        }
    }
}

@Composable
private fun PortfolioRow(
    row: PortfolioRowModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.small
    val highlight = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.xxxl) // cible tactile ≥ 48 dp
            .clip(shape)
            .then(if (row.isActive) Modifier.background(highlight) else Modifier)
            .clickable(onClickLabel = "Afficher ce portefeuille", role = Role.Button, onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                // Une seule phrase TalkBack par ligne ; le nœud cliquable parent porte le rôle bouton.
                .clearAndSetSemantics { contentDescription = row.spokenDescription },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.isActive) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(IconSize.sm),
                        )
                        Text(
                            text = "Actif",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            AmountColumn(
                value = row.value,
                pnl = row.pnl,
                pnlTint = pnlToneColor(row.tone),
            )
        }
    }
}

@Composable
private fun TotalRow(
    total: PortfolioTotalModel,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.xxxl)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
            .clearAndSetSemantics { contentDescription = total.spokenDescription },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            text = "Total",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        AmountColumn(
            value = total.value,
            pnl = total.pnl,
            pnlTint = pnlToneColor(total.tone),
        )
    }
}

/** Valeur (mono, alignée à droite) puis P&L de la période dans la couleur de son signe. */
@Composable
private fun AmountColumn(
    value: String,
    pnl: String?,
    pnlTint: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        FitText(
            text = value,
            style = MaterialTheme.typography.bodyMedium.asNumeric(),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
        if (pnl != null) {
            FitText(
                text = pnl,
                style = MaterialTheme.typography.labelMedium.asNumeric(),
                color = pnlTint,
                textAlign = TextAlign.End,
            )
        }
    }
}
