package com.tradingplatform.app.ui.screens.dashboard.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.tradingplatform.app.ui.components.TradingCard
import com.tradingplatform.app.ui.screens.dashboard.DashboardKpi
import com.tradingplatform.app.ui.screens.dashboard.PnlTone
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing
import com.tradingplatform.app.ui.theme.asNumeric

/** Tuiles par ligne : 2 colonnes (~150 dp) — 3 colonnes tronquaient « +12 345 € » à police 130 %. */
private const val KPI_COLUMNS = 2

/**
 * Petites tuiles KPI (Liquidités, Latent, Win rate, Drawdown max), en mono, sur deux colonnes.
 * Chaque tuile est cliquable et mène à l'écran Performance ([onClick]). Un nombre impair de tuiles
 * laisse la dernière seule sur sa ligne, pleine largeur. Rien n'est rendu si [kpis] est vide.
 */
@Composable
internal fun DashboardKpiRow(
    kpis: List<DashboardKpi>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (kpis.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        kpis.chunked(KPI_COLUMNS).forEach { rowKpis ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                rowKpis.forEach { kpi ->
                    KpiTile(
                        kpi = kpi,
                        onClick = onClick,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun KpiTile(
    kpi: DashboardKpi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TradingCard(
        modifier = modifier,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                // Une seule phrase TalkBack (« Liquidités : 12 000,00 € ») ; le nœud de la
                // carte porte déjà le rôle bouton et l'action de clic.
                .clearAndSetSemantics { contentDescription = kpi.spokenDescription },
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            Text(
                text = kpi.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            // FitText : la valeur rétrécit plutôt que d'être tronquée (police 130 %).
            FitText(
                text = kpi.value,
                style = MaterialTheme.typography.titleMedium.asNumeric(),
                color = pnlToneColor(kpi.tone),
            )
        }
    }
}

/** Couleur d'un [PnlTone] : couleurs P&L du thème, texte courant pour le neutre. */
@Composable
internal fun pnlToneColor(tone: PnlTone): Color {
    val extendedColors = LocalExtendedColors.current
    return when (tone) {
        PnlTone.POSITIVE -> extendedColors.pnlPositive
        PnlTone.NEGATIVE -> extendedColors.pnlNegative
        PnlTone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
    }
}
