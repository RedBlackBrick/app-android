package com.tradingplatform.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tradingplatform.app.ui.theme.Spacing

/**
 * Sections de l'onglet « Portefeuille » : positions ouvertes, ordres et historique des
 * transactions. Chaque section est une route de navigation distincte (voir
 * `ui/navigation/PortfolioNavigation.kt`) ; ce composant ne fait que rendre le sélecteur.
 */
enum class PortfolioSegment(val label: String) {
    Positions("Positions"),
    Orders("Ordres"),
    History("Historique"),
}

/** Cible tactile minimale (Material / TalkBack) — hauteur de chaque segment. */
private val MinTouchTarget = 48.dp

/**
 * Rangée de segments (choix unique) affichée en haut des trois écrans du Portefeuille, sous la
 * `TopAppBar`. Choisir un segment appelle [onSelect] ; l'écran hôte y branche la navigation vers
 * la route correspondante. Re-sélectionner le segment courant appelle aussi [onSelect] (la
 * navigation est idempotente : `launchSingleTop`).
 *
 * Pas d'icône « coche » sur le segment actif : trois libellés sur ~360 dp seraient tronqués.
 * L'état sélectionné reste lisible via la couleur du conteneur et la sémantique du bouton
 * (rôle « bouton radio » + état sélectionné annoncés par TalkBack).
 */
@Composable
fun PortfolioSegmentedTabs(
    selected: PortfolioSegment,
    onSelect: (PortfolioSegment) -> Unit,
    modifier: Modifier = Modifier,
) {
    val segments = PortfolioSegment.entries
    SingleChoiceSegmentedButtonRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
    ) {
        segments.forEachIndexed { index, segment ->
            SegmentedButton(
                selected = segment == selected,
                onClick = { onSelect(segment) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = segments.size),
                modifier = Modifier.heightIn(min = MinTouchTarget),
                icon = {},
                label = {
                    Text(
                        text = segment.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}
