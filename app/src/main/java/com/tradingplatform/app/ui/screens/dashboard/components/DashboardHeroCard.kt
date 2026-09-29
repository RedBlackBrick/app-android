package com.tradingplatform.app.ui.screens.dashboard.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.tradingplatform.app.data.local.db.CacheTtl
import com.tradingplatform.app.domain.model.NavSummary
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.PnlSummary
import com.tradingplatform.app.ui.common.DataState
import com.tradingplatform.app.ui.components.AnimatedPnlText
import com.tradingplatform.app.ui.components.CacheTimestamp
import com.tradingplatform.app.ui.components.ErrorBanner
import com.tradingplatform.app.ui.components.MoneyText
import com.tradingplatform.app.ui.components.ShimmerBox
import com.tradingplatform.app.ui.components.SparklineChart
import com.tradingplatform.app.ui.components.TradingCard
import com.tradingplatform.app.ui.components.tradingSegmentedButtonColors
import com.tradingplatform.app.ui.components.formatMoneyAmount
import com.tradingplatform.app.ui.screens.dashboard.DASHBOARD_PERIODS
import com.tradingplatform.app.ui.screens.dashboard.HeroFooter
import com.tradingplatform.app.ui.screens.dashboard.NO_VALUE
import com.tradingplatform.app.ui.screens.dashboard.dashboardPeriodLabel
import com.tradingplatform.app.ui.screens.dashboard.formatPercent
import com.tradingplatform.app.ui.screens.dashboard.heroFooter
import com.tradingplatform.app.ui.screens.dashboard.percentTone
import com.tradingplatform.app.ui.screens.dashboard.variationSpokenDescription
import com.tradingplatform.app.ui.theme.Spacing
import com.tradingplatform.app.ui.theme.asNumeric

/**
 * Carte « héros » du Dashboard : la NAV en très grand, la variation (P&L) de la période
 * sélectionnée juste dessous, la sparkline, puis le sélecteur de période.
 *
 * Les valeurs viennent des [DataState] existants : une valeur déjà chargée n'est jamais
 * remplacée par un skeleton pendant un refresh (le pied de carte signale une donnée périmée).
 */
@Composable
internal fun DashboardHeroCard(
    navState: DataState<NavSummary>,
    pnlState: DataState<PnlSummary>,
    selectedPeriod: PnlPeriod,
    onSelectPeriod: (PnlPeriod) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TradingCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            NavHero(navState = navState)
            VariationLine(pnlState = pnlState)

            // Sparkline sans titre, rattachée au héros (même source de données qu'avant).
            val sparklinePoints = pnlState.value?.sparklinePoints.orEmpty()
            if (sparklinePoints.size >= 2) {
                SparklineChart(
                    dataPoints = sparklinePoints,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            PeriodSelector(
                selected = selectedPeriod,
                onSelect = onSelectPeriod,
            )

            when (val footer = heroFooter(navState, pnlState)) {
                HeroFooter.None -> Unit
                is HeroFooter.Stale -> CacheTimestamp(
                    syncedAt = footer.syncedAt,
                    ttlMs = CacheTtl.PNL_MS,
                )
                is HeroFooter.Error -> ErrorBanner(
                    message = footer.message,
                    onRetry = onRetry,
                )
            }
        }
    }
}

// ── NAV ──────────────────────────────────────────────────────────────────────

@Composable
private fun NavHero(
    navState: DataState<NavSummary>,
    modifier: Modifier = Modifier,
) {
    val nav = navState.value
    Column(
        modifier = if (nav != null) {
            // Un seul nœud TalkBack : « Valeur liquidative : 100 000,00 € ».
            modifier.clearAndSetSemantics {
                contentDescription = "Valeur liquidative : ${formatMoneyAmount(nav.currentValue, "€")}"
            }
        } else {
            modifier
        },
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            text = "Valeur liquidative",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            nav != null -> MoneyText(
                amount = nav.currentValue,
                style = MaterialTheme.typography.headlineLarge,
            )
            navState.error != null -> Text(
                text = "Indisponible",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.error,
            )
            else -> Text(
                text = NO_VALUE,
                style = MaterialTheme.typography.headlineLarge.asNumeric(),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// ── Variation de la période ──────────────────────────────────────────────────

/**
 * « P&L  +4 500,00 €  +4,50% » — montant (`totalReturn`) et pourcentage (`totalReturnPct`) de la
 * période sélectionnée, colorés selon le signe. Libellé « P&L » repris de l'ancienne carte.
 */
@Composable
private fun VariationLine(
    pnlState: DataState<PnlSummary>,
    modifier: Modifier = Modifier,
) {
    val pnl = pnlState.value
    Row(
        modifier = if (pnl != null) {
            // Le montant animé et le pourcentage sont lus d'un seul tenant.
            modifier.clearAndSetSemantics {
                contentDescription = variationSpokenDescription(pnl.totalReturn, pnl.totalReturnPct)
            }
        } else {
            modifier
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = "P&L",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            pnl != null -> {
                val totalReturn = pnl.totalReturn
                val totalReturnPct = pnl.totalReturnPct
                if (totalReturn != null) {
                    AnimatedPnlText(
                        value = totalReturn,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (totalReturnPct != null) {
                    Text(
                        text = formatPercent(totalReturnPct, signed = true),
                        style = MaterialTheme.typography.titleMedium.asNumeric(),
                        color = pnlToneColor(percentTone(totalReturnPct)),
                    )
                }
                if (totalReturn == null && totalReturnPct == null) {
                    Text(
                        text = NO_VALUE,
                        style = MaterialTheme.typography.titleMedium.asNumeric(),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            // Changement de période : la section P&L repart de zéro le temps du fetch.
            pnlState.isInitialLoading -> ShimmerBox(width = 140.dp, height = 20.dp)
            pnlState.error != null -> Text(
                text = "Indisponible",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            else -> Text(
                text = NO_VALUE,
                style = MaterialTheme.typography.titleMedium.asNumeric(),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// ── Sélecteur de période ─────────────────────────────────────────────────────

/**
 * Sélecteur Material 3 à choix unique (Jour / Semaine / Mois / Année). Le rôle et l'état
 * « sélectionné » sont exposés à TalkBack par [SegmentedButton] ; l'icône de coche est retirée
 * pour tenir sur 4 segments à largeur de téléphone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodSelector(
    selected: PnlPeriod,
    onSelect: (PnlPeriod) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        DASHBOARD_PERIODS.forEachIndexed { index, period ->
            SegmentedButton(
                selected = period == selected,
                onClick = { onSelect(period) },
                shape = SegmentedButtonDefaults.itemShape(index, DASHBOARD_PERIODS.size),
                colors = tradingSegmentedButtonColors(),
                icon = {},
                label = {
                    Text(
                        text = dashboardPeriodLabel(period),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                },
            )
        }
    }
}
