package com.tradingplatform.app.ui.screens.dashboard.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.tradingplatform.app.data.local.db.CacheTtl
import com.tradingplatform.app.domain.model.NavCurve
import com.tradingplatform.app.domain.model.NavSummary
import com.tradingplatform.app.domain.model.PnlPeriod
import com.tradingplatform.app.domain.model.PnlSummary
import com.tradingplatform.app.ui.common.DataState
import com.tradingplatform.app.ui.components.AnimatedPnlText
import com.tradingplatform.app.ui.components.CacheTimestamp
import com.tradingplatform.app.ui.components.ErrorBanner
import com.tradingplatform.app.ui.components.ShimmerBox
import com.tradingplatform.app.ui.components.SparklineChart
import com.tradingplatform.app.ui.components.TradingCard
import com.tradingplatform.app.ui.components.formatMoneyAmount
import com.tradingplatform.app.ui.components.tradingSegmentedButtonColors
import com.tradingplatform.app.ui.screens.dashboard.BrokerPillModel
import com.tradingplatform.app.ui.screens.dashboard.BrokerTone
import com.tradingplatform.app.ui.screens.dashboard.DASHBOARD_PERIODS
import com.tradingplatform.app.ui.screens.dashboard.HeroFooter
import com.tradingplatform.app.ui.screens.dashboard.NO_VALUE
import com.tradingplatform.app.ui.screens.dashboard.dashboardPeriodLabel
import com.tradingplatform.app.ui.screens.dashboard.formatPercent
import com.tradingplatform.app.ui.screens.dashboard.heroFooter
import com.tradingplatform.app.ui.screens.dashboard.navCurveSpokenDescription
import com.tradingplatform.app.ui.screens.dashboard.navCurveValues
import com.tradingplatform.app.ui.screens.dashboard.percentTone
import com.tradingplatform.app.ui.screens.dashboard.variationSpokenDescription
import com.tradingplatform.app.ui.theme.IconSize
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing
import com.tradingplatform.app.ui.theme.asNumeric

/**
 * Carte « héros » du Dashboard : la NAV en très grand, la variation (P&L) de la période
 * sélectionnée juste dessous, la courbe de NAV, le sélecteur de période, puis la pastille broker.
 *
 * Les valeurs viennent des [DataState] existants : une valeur déjà chargée n'est jamais
 * remplacée par un skeleton pendant un refresh (le pied de carte signale une donnée périmée).
 *
 * @param navCurve courbe de NAV de la période ; absente / vide / en échec → aucune courbe (jamais
 *   un tracé de remplacement). Un léger squelette réserve sa place pendant le chargement afin que
 *   le sélecteur de période ne saute pas sous le doigt.
 * @param brokerPill pastille « Broker : … » ; `null` (aucune connexion) → rien.
 * @param currencySymbol symbole de la devise du portefeuille actif.
 */
@Composable
internal fun DashboardHeroCard(
    navState: DataState<NavSummary>,
    pnlState: DataState<PnlSummary>,
    navCurve: DataState<NavCurve>,
    selectedPeriod: PnlPeriod,
    onSelectPeriod: (PnlPeriod) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    brokerPill: BrokerPillModel? = null,
    currencySymbol: String = "€",
) {
    TradingCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            NavHero(navState = navState, currencySymbol = currencySymbol)
            VariationLine(pnlState = pnlState, currencySymbol = currencySymbol)

            NavCurveSection(navCurve = navCurve, currencySymbol = currencySymbol)

            PeriodSelector(
                selected = selectedPeriod,
                onSelect = onSelectPeriod,
            )

            if (brokerPill != null) {
                BrokerPill(model = brokerPill)
            }

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
    currencySymbol: String,
    modifier: Modifier = Modifier,
) {
    val nav = navState.value
    Column(
        modifier = if (nav != null) {
            // Un seul nœud TalkBack : « Valeur liquidative : 100 000,00 € ».
            modifier.clearAndSetSemantics {
                contentDescription =
                    "Valeur liquidative : ${formatMoneyAmount(nav.currentValue, currencySymbol)}"
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
            // FitText : la NAV rétrécit plutôt que d'être tronquée (360 dp, police 130 %).
            nav != null -> FitText(
                text = formatMoneyAmount(nav.currentValue, currencySymbol),
                style = MaterialTheme.typography.headlineLarge.asNumeric(),
                color = MaterialTheme.colorScheme.onSurface,
                minFontSize = MaterialTheme.typography.titleLarge.fontSize,
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
    currencySymbol: String,
    modifier: Modifier = Modifier,
) {
    val pnl = pnlState.value
    Row(
        modifier = if (pnl != null) {
            // Le montant animé et le pourcentage sont lus d'un seul tenant.
            modifier.clearAndSetSemantics {
                contentDescription =
                    variationSpokenDescription(pnl.totalReturn, pnl.totalReturnPct, currencySymbol)
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
                        currencySymbol = currencySymbol,
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

// ── Courbe de NAV ────────────────────────────────────────────────────────────

/**
 * Courbe de NAV de la période sélectionnée (composant [SparklineChart] existant).
 *
 * - au moins 2 points → courbe, décrite à TalkBack par une phrase en euros formatés (la description
 *   native du composant contiendrait des BigDecimal bruts : elle est masquée) ;
 * - premier chargement (ni valeur ni erreur) → squelette de même hauteur ;
 * - vide, absente ou en échec → RIEN (jamais un placeholder trompeur).
 */
@Composable
private fun NavCurveSection(
    navCurve: DataState<NavCurve>,
    currencySymbol: String,
    modifier: Modifier = Modifier,
) {
    val curve = navCurve.value
    val values = remember(curve) { navCurveValues(curve) }
    when {
        values.isNotEmpty() -> {
            val description = navCurveSpokenDescription(values, currencySymbol)
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics {
                        if (description != null) {
                            contentDescription = description
                        }
                    },
            ) {
                SparklineChart(
                    dataPoints = values,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        navCurve.isInitialLoading -> ShimmerBox(
            width = 300.dp,
            height = Spacing.xxxl + Spacing.xxl, // = hauteur par défaut de SparklineChart
            modifier = modifier.fillMaxWidth(),
        )
        else -> Unit
    }
}

// ── Broker ───────────────────────────────────────────────────────────────────

/**
 * « Broker : ALPACA » — statut de CONFIGURATION du broker, jamais une santé en direct (aucun
 * « connecté » / « en ligne »). Un statut dégradé ajoute une icône d'avertissement et le libellé
 * (le signal ne repose pas sur la seule couleur). Le libellé peut passer sur 2 lignes (police 130 %).
 */
@Composable
private fun BrokerPill(
    model: BrokerPillModel,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current
    val textColor = when (model.tone) {
        BrokerTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        BrokerTone.WARNING -> extendedColors.onWarningContainer
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = model.spokenDescription },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        if (model.tone == BrokerTone.WARNING) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = extendedColors.warning,
                modifier = Modifier.size(IconSize.sm),
            )
        }
        Text(
            text = model.label,
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
            maxLines = 2,
        )
    }
}

// ── Sélecteur de période ─────────────────────────────────────────────────────

/**
 * Sélecteur Material 3 à choix unique (Jour / Sem. / Mois / Tout). Le rôle et l'état
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
