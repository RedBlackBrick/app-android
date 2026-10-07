package com.tradingplatform.app.ui.screens.vigie

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradingplatform.app.data.local.db.CacheTtl
import com.tradingplatform.app.domain.model.Vigie
import com.tradingplatform.app.domain.model.VigieFamily
import com.tradingplatform.app.domain.model.VigieIndicator
import com.tradingplatform.app.domain.model.VigieRefuge
import com.tradingplatform.app.domain.model.VigieRegime
import com.tradingplatform.app.ui.components.CacheTimestamp
import com.tradingplatform.app.ui.components.ErrorBanner
import com.tradingplatform.app.ui.components.SkeletonDashboardCard
import com.tradingplatform.app.ui.components.TradingCard
import com.tradingplatform.app.ui.theme.Spacing
import com.tradingplatform.app.ui.theme.asNumeric

/**
 * Écran détail de la Vigie « Refuge » (poussé depuis la carte Accueil ou le widget) : score et
 * niveau, famille la plus tendue + les 5 familles, courbe 90 j, familles → indicateurs (valeur,
 * score, statut, âge, seuils, détail), panneau régime, refuge (jambes), couverture, méthodologie
 * courte et horodatage de sync. Veille COÏNCIDENTE, jamais une prévision.
 *
 * Ne dépend pas du réglage de compte `showVigie`. Pull-to-refresh = `forceRefresh` ; hors ligne =
 * dernière valeur en cache datée (jamais d'erreur bloquante tant qu'une valeur existe).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VigieScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VigieViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(VIGIE_TITLE) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Retour",
                        )
                    }
                },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        val state = uiState
        PullToRefreshBox(
            isRefreshing = state is VigieUiState.Success && state.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                when (state) {
                    VigieUiState.Loading -> {
                        SkeletonDashboardCard()
                        SkeletonDashboardCard()
                    }

                    is VigieUiState.Error -> ErrorBanner(
                        message = state.message,
                        onRetry = { viewModel.refresh() },
                    )

                    is VigieUiState.Success -> {
                        state.staleError?.let { ErrorBanner(message = it, onRetry = { viewModel.refresh() }) }
                        VigieContent(vigie = state.vigie, syncedAt = state.syncedAt)
                    }
                }
            }
        }
    }
}

@Composable
private fun VigieContent(vigie: Vigie, syncedAt: Long) {
    val model = remember(vigie) { vigieCardModel(vigie) }

    // ── En-tête : score, niveau, famille la plus tendue, familles, refuge, courbe ──
    TradingCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text(
                    text = model.scoreText,
                    style = MaterialTheme.typography.displaySmall.asNumeric(),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (model.hasLevel) {
                    Text(
                        text = "/ $VIGIE_SCORE_MAX",
                        style = MaterialTheme.typography.bodyLarge.asNumeric(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                VigieLevelChip(label = model.levelLabel, tone = model.tone)
                if (model.showStaleBadge) VigieStaleBadge()
            }

            if (!vigie.available) {
                Text(
                    text = "Aucun calcul n'est encore disponible côté serveur.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            rawLevelNote(vigie)?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            model.stressedText?.let { text ->
                if (model.masksStress) {
                    Surface(
                        color = model.stressedTone.containerColor(),
                        contentColor = model.stressedTone.onContainerColor(),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Column(
                            modifier = Modifier.padding(Spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                        ) {
                            Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text(VIGIE_MASK_NOTE, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                } else {
                    Text(text = text, style = MaterialTheme.typography.bodyMedium)
                }
            }

            VigieFamilyBars(families = model.families)
            model.refugeText?.let { VigieLevelChip(label = it, tone = model.refugeTone) }

            model.sparkline?.let { values ->
                Text(
                    text = "Score sur ${values.size} jours ouvrés",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                VigieSparkline(
                    values = values,
                    cutoffs = vigie.cutoffs,
                    lineColor = model.tone.accentColor(),
                    description = "Courbe du score global sur ${values.size} jours ouvrés",
                )
                cutoffsLine(vigie.cutoffs)?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Text(
                text = listOfNotNull(model.coverageText, model.asOfText?.let { "calcul du $it" })
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CacheTimestamp(syncedAt = syncedAt, ttlMs = CacheTtl.VIGIE_MS)
            Text(
                text = VIGIE_DISCLAIMER,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = VIGIE_COINCIDENT_NOTE,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // ── Familles → indicateurs ──
    vigie.families.forEach { family ->
        FamilyCard(family = family, indicators = indicatorsOf(vigie, family.code))
    }

    vigie.regime?.takeIf { it.indicators.isNotEmpty() }?.let { RegimeCard(it) }
    vigie.refuge?.let { RefugeCard(it) }

    TradingCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            SectionTitle("Méthodologie")
            Text(
                text = methodologyText(vigie.methodologyVersion),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.semantics { heading() },
    )
}

// ── Famille ─────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun FamilyCard(family: VigieFamily, indicators: List<VigieIndicator>) {
    TradingCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Column(modifier = Modifier.weight(1f)) {
                    SectionTitle("${family.code} · ${family.label}")
                    Text(
                        text = formatCoverage(family.live, family.expected),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                VigieLevelChip(
                    label = if (family.score == null) VIGIE_NO_VALUE else "${formatVigieNumber(family.score)} / $VIGIE_FAMILY_SCORE_MAX",
                    tone = familyTone(family.score),
                )
            }
            indicators.forEachIndexed { index, indicator ->
                if (index > 0) HorizontalDivider()
                IndicatorRow(indicator)
            }
        }
    }
}

@Composable
private fun IndicatorRow(indicator: VigieIndicator) {
    val live = isIndicatorLive(indicator)
    val contentColor = if (live) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                text = indicator.label,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatIndicatorValue(indicator.value, indicator.unit),
                style = MaterialTheme.typography.bodyMedium.asNumeric(),
                color = contentColor,
                textAlign = TextAlign.End,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            VigieLevelChip(label = indicatorScoreLabel(indicator.score), tone = indicatorTone(indicator.score))
            Text(
                text = "${indicatorStatusLabel(indicator.status)} · âge ${formatAgeDays(indicator.ageDays)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = formatThresholds(indicator),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (indicator.detail.isNotBlank()) {
            Text(
                text = indicator.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Régime ──────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun RegimeCard(regime: VigieRegime) {
    TradingCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            SectionTitle("Régime (informatif, hors score)")
            regime.indicators.forEachIndexed { index, indicator ->
                if (index > 0) HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(
                            text = indicator.label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = formatIndicatorValue(indicator.value, indicator.unit),
                            style = MaterialTheme.typography.bodyMedium.asNumeric(),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        VigieLevelChip(label = regimeFlagLabel(indicator.flag), tone = regimeFlagTone(indicator.flag))
                        Text(
                            text = "${indicatorStatusLabel(indicator.status)} · âge ${formatAgeDays(indicator.ageDays)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (indicator.detail.isNotBlank()) {
                        Text(
                            text = indicator.detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ── Refuge ──────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun RefugeCard(refuge: VigieRefuge) {
    TradingCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Column(modifier = Modifier.weight(1f)) {
                    SectionTitle("Refuge (informatif, hors score)")
                    Text(
                        text = refugeSummary(refuge.legsMet, refuge.legs.size, refuge.legsUnknown),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                VigieLevelChip(label = refugeLabel(refuge.state), tone = refugeTone(refuge.state))
            }
            refuge.legs.forEachIndexed { index, leg ->
                if (index > 0) HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(
                            text = leg.label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        VigieLevelChip(label = refugeLegLabel(leg), tone = refugeLegTone(leg))
                    }
                    if (leg.detail.isNotBlank()) {
                        Text(
                            text = leg.detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
