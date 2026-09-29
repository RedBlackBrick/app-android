package com.tradingplatform.app.ui.screens.dashboard.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.tradingplatform.app.ui.screens.dashboard.RiskBannerModel
import com.tradingplatform.app.ui.theme.IconSize
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing

/**
 * Bandeau de risque : l'UNIQUE alerte risque de l'Accueil (la plus grave — cf. `riskBannerModel`),
 * affichée en haut de l'écran seulement quand il y a quelque chose à signaler. Toucher le bandeau
 * ouvre l'écran Risque ([onClick]).
 *
 * Trading bloqué (kill switch, circuit-breaker, statut inconnu) → conteneur d'erreur ; sinon
 * conteneur d'avertissement. Le signal ne repose pas sur la seule couleur : icône d'avertissement +
 * libellé explicite. Données de risque incomplètes → mention discrète « Données partielles ».
 */
@Composable
internal fun DashboardRiskBanner(
    model: RiskBannerModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current
    // Paires conteneur / contenu du thème : contraste garanti par construction, clair comme sombre.
    val containerColor = if (model.isBlocking) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        extendedColors.warningContainer
    }
    val contentColor = if (model.isBlocking) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        extendedColors.onWarningContainer
    }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.xxxl)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                .clearAndSetSemantics { contentDescription = model.spokenDescription },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(IconSize.md),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                Text(
                    text = model.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor,
                )
                if (model.isPartial) {
                    Text(
                        text = "Données partielles",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor,
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = contentColor,
            )
        }
    }
}
