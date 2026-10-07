package com.tradingplatform.app.ui.screens.dashboard.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import com.tradingplatform.app.data.local.db.CacheTtl
import com.tradingplatform.app.domain.model.Vigie
import com.tradingplatform.app.ui.common.DataState
import com.tradingplatform.app.ui.components.CacheTimestamp
import com.tradingplatform.app.ui.components.TradingCard
import com.tradingplatform.app.ui.screens.vigie.VIGIE_DISCLAIMER
import com.tradingplatform.app.ui.screens.vigie.VIGIE_MASK_NOTE
import com.tradingplatform.app.ui.screens.vigie.VIGIE_SCORE_MAX
import com.tradingplatform.app.ui.screens.vigie.VIGIE_TITLE
import com.tradingplatform.app.ui.screens.vigie.VigieFamilyBars
import com.tradingplatform.app.ui.screens.vigie.VigieLevelChip
import com.tradingplatform.app.ui.screens.vigie.VigieSparkline
import com.tradingplatform.app.ui.screens.vigie.VigieStaleBadge
import com.tradingplatform.app.ui.screens.vigie.accentColor
import com.tradingplatform.app.ui.screens.vigie.containerColor
import com.tradingplatform.app.ui.screens.vigie.onContainerColor
import com.tradingplatform.app.ui.screens.vigie.vigieCardModel
import com.tradingplatform.app.ui.theme.Spacing
import com.tradingplatform.app.ui.theme.asNumeric

/**
 * Carte « Vigie » de l'Accueil — veille COÏNCIDENTE de la tension de marché (jamais une prévision).
 * Affichée seulement quand le réglage de compte `ui.appearance.showVigie` est vrai ; sa section a
 * son propre état ([state]) et sa propre erreur : elle n'entre jamais dans les skeletons NAV/PnL.
 *
 * - score (1 décimale) + niveau en pastille (jeton du thème) ; `unknown` / `available=false` →
 *   neutre « Données insuffisantes », jamais vert ;
 * - « Famille la plus tendue : … » TOUJOURS visible à côté du score, et les 5 familles en
 *   mini-barres : un global Normal avec une famille rouge ne se lit pas « tout va bien » (note
 *   explicite quand le global est plus doux que la famille) ;
 * - refuge, courbe 90 j, couverture « 14/14 indicateurs », date du calcul, badge « périmé » ;
 * - horodatage de sync ([CacheTimestamp], TTL [CacheTtl.VIGIE_MS]) ; tap → écran détail.
 */
@Composable
internal fun DashboardVigieCard(
    state: DataState<Vigie>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vigie = state.value
    TradingCard(onClick = onClick, modifier = modifier) {
        when {
            vigie != null -> VigieCardContent(vigie = vigie, syncedAt = state.syncedAt, error = state.error)
            state.error != null -> VigieCardMessage(text = state.error)
            else -> VigieCardMessage(text = "Chargement…")
        }
    }
}

@Composable
private fun VigieCardContent(vigie: Vigie, syncedAt: Long, error: String?) {
    val model = remember(vigie) { vigieCardModel(vigie) }
    val cutoffs = vigie.cutoffs

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.lg)
            .clearAndSetSemantics { contentDescription = model.spokenDescription },
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = VIGIE_TITLE,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (model.showStaleBadge) VigieStaleBadge()
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = model.scoreText,
                style = MaterialTheme.typography.headlineMedium.asNumeric(),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (model.hasLevel) {
                Text(
                    text = "/ $VIGIE_SCORE_MAX",
                    style = MaterialTheme.typography.bodyMedium.asNumeric(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            VigieLevelChip(label = model.levelLabel, tone = model.tone)
        }

        model.stressedText?.let { text ->
            if (model.masksStress) {
                // Le global est plus doux que la famille la plus tendue : callout en paire
                // conteneur / contenu du thème (contraste garanti) + note explicite.
                Surface(
                    color = model.stressedTone.containerColor(),
                    contentColor = model.stressedTone.onContainerColor(),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Column(
                        modifier = Modifier.padding(Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(text = VIGIE_MASK_NOTE, style = MaterialTheme.typography.labelSmall)
                    }
                }
            } else {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        VigieFamilyBars(families = model.families)

        model.refugeText?.let { text ->
            VigieLevelChip(label = text, tone = model.refugeTone)
        }

        model.sparkline?.let { values ->
            VigieSparkline(
                values = values,
                cutoffs = cutoffs,
                lineColor = model.tone.accentColor(),
                description = "Courbe du score sur ${values.size} jours ouvrés",
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = listOfNotNull(model.coverageText, model.asOfText).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            CacheTimestamp(syncedAt = syncedAt, ttlMs = CacheTtl.VIGIE_MS)
        }

        // Valeur périmée conservée + dernier rafraîchissement en échec : jamais d'erreur bloquante.
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Text(
            text = VIGIE_DISCLAIMER,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Chargement initial ou échec sans aucune valeur : message neutre (jamais un niveau, jamais vert). */
@Composable
private fun VigieCardMessage(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            text = VIGIE_TITLE,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = VIGIE_DISCLAIMER,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
