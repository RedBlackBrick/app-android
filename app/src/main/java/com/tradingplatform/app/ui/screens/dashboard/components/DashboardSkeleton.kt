package com.tradingplatform.app.ui.screens.dashboard.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tradingplatform.app.ui.components.ShimmerBox
import com.tradingplatform.app.ui.components.TradingCard
import com.tradingplatform.app.ui.theme.Spacing

private const val SKELETON_KPI_ROWS = 2
private const val SKELETON_KPI_COLUMNS = 2
private const val SKELETON_ACTIVITY_ROWS = 3

/**
 * Squelette du Dashboard, affiché au TOUT premier chargement uniquement (NAV et P&L sans
 * valeur ni erreur). Reproduit la structure de l'écran : héros, tuiles KPI (2 colonnes), activité.
 * Les blocs optionnels (bandeau de risque, « Mes portefeuilles », broker, stratégies) n'ont pas de
 * squelette : ils apparaissent quand leurs données arrivent.
 */
@Composable
internal fun DashboardSkeleton(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        // Héros : libellé, NAV, variation, graphique, sélecteur
        TradingCard {
            Column(
                modifier = Modifier.padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                ShimmerBox(width = 120.dp, height = 12.dp)
                ShimmerBox(width = 220.dp, height = 36.dp)
                ShimmerBox(width = 160.dp, height = 20.dp)
                ShimmerBox(width = 300.dp, height = 64.dp, modifier = Modifier.fillMaxWidth())
                ShimmerBox(width = 300.dp, height = 40.dp, modifier = Modifier.fillMaxWidth())
            }
        }

        // Tuiles KPI (grille 2 × 2)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            repeat(SKELETON_KPI_ROWS) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    repeat(SKELETON_KPI_COLUMNS) {
                        TradingCard(modifier = Modifier.weight(1f)) {
                            Column(
                                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                            ) {
                                ShimmerBox(width = 56.dp, height = 10.dp)
                                ShimmerBox(width = 64.dp, height = 18.dp)
                            }
                        }
                    }
                }
            }
        }

        // Activité
        TradingCard {
            Column(
                modifier = Modifier.padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                ShimmerBox(width = 80.dp, height = 14.dp)
                repeat(SKELETON_ACTIVITY_ROWS) {
                    ShimmerBox(width = 300.dp, height = 28.dp, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
