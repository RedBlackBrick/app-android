package com.tradingplatform.app.ui.screens.alerts

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tradingplatform.app.ui.components.tradingSegmentedButtonColors
import com.tradingplatform.app.ui.theme.Spacing

/** Cible tactile minimale (Material / TalkBack) — hauteur de chaque segment. */
private val MinTouchTarget = 48.dp

/**
 * Sélecteur à deux segments en tête de l'écran Alertes : « Cet appareil » (alertes FCM locales) /
 * « Serveur » (boîte de réception serveur).
 *
 * Les libellés ne sont jamais tronqués : à police agrandie (130 % sur ~360 dp) « Cet appareil »
 * passe à la ligne (2 lignes max) au lieu d'être coupé par une ellipse. Pas d'icône « coche » sur
 * le segment actif ; l'état sélectionné reste lisible par la couleur du conteneur (voir
 * [tradingSegmentedButtonColors]) et par la sémantique du bouton (rôle « bouton radio » + état
 * sélectionné annoncés par TalkBack).
 */
@Composable
internal fun AlertsSegmentedTabs(
    selected: AlertsSegment,
    onSelect: (AlertsSegment) -> Unit,
    modifier: Modifier = Modifier,
) {
    val segments = AlertsSegment.entries
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
                colors = tradingSegmentedButtonColors(),
                icon = {},
                label = {
                    Text(
                        text = segment.label,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                    )
                },
            )
        }
    }
}
