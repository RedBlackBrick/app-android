package com.tradingplatform.app.ui.screens.strategies

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradingplatform.app.domain.model.PortfolioStrategyEntry
import com.tradingplatform.app.ui.components.CacheTimestamp
import com.tradingplatform.app.ui.components.ConfirmActionSheet
import com.tradingplatform.app.ui.components.EmptyState
import com.tradingplatform.app.ui.components.ErrorBanner
import com.tradingplatform.app.ui.components.PortfolioSwitcher
import com.tradingplatform.app.ui.components.SkeletonDeviceCard
import com.tradingplatform.app.ui.components.TradingCard
import com.tradingplatform.app.ui.theme.IconSize
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing
import com.tradingplatform.app.ui.theme.TradingPlatformTheme

/** Hauteur minimale d'une cible tactile (Material / TalkBack). */
private val MinTouchTarget = 48.dp

/**
 * Écran poussé « Stratégies » du portefeuille ACTIF : un lien portefeuille-stratégie par carte,
 * avec son état (pastille icône + texte) et l'action « Mettre en pause » / « Réactiver ce lien »
 * (garde d'écriture → confirmation biométrique → demande → relecture, voir [StrategiesViewModel]).
 *
 * Lecture + pause / reprise uniquement : l'édition (allocation, paramètres, rattachement) reste sur
 * le web — un rappel est affiché en bas de liste.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StrategiesScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StrategiesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Résultat d'écriture, échec, garde bloquée… : un snackbar à la fois, consommé une fois affiché.
    // showSnackbar est annulable : un nouveau message relance l'effet et remplace le précédent.
    val message = uiState.message
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(
                message = message,
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            viewModel.messageShown(message)
        }
    }

    // Feuille de confirmation (récapitulatif → biométrie à chaque action, fail-closed).
    ConfirmActionSheet(
        action = uiState.confirmation?.action,
        onDismiss = viewModel::dismissConfirmation,
        onConfirmed = { viewModel.confirmPending() },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Stratégies",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Retour",
                        )
                    }
                },
                actions = { PortfolioSwitcher() },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        modifier = modifier,
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            StrategiesList(
                state = uiState,
                onToggle = viewModel::requestToggle,
                onRetry = viewModel::refresh,
            )
        }
    }
}

@Composable
private fun StrategiesList(
    state: StrategiesUiState,
    onToggle: (strategyId: String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        when (val content = state.content) {
            is StrategiesContent.Loading -> {
                items(3) { SkeletonDeviceCard() }
            }

            is StrategiesContent.Error -> {
                item(key = "error") {
                    ErrorBanner(message = content.message, onRetry = onRetry)
                }
            }

            is StrategiesContent.Success -> {
                if (content.entries.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            illustration = {
                                Icon(
                                    imageVector = Icons.Filled.ShowChart,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(IconSize.lg),
                                )
                            },
                            title = "Aucune stratégie",
                            message = "Ce portefeuille n'a aucune stratégie liée.",
                            modifier = Modifier.padding(vertical = Spacing.xxl),
                        )
                    }
                } else {
                    item(key = "header") {
                        StrategiesHeader(entries = content.entries, syncedAt = state.syncedAt)
                    }
                    val actionsEnabled = canStartStrategyWrite(state.write, state.isRefreshing)
                    items(content.entries, key = { it.strategyId }) { entry ->
                        val busy = state.write?.takeIf {
                            it.portfolioId == state.portfolioId && it.strategyId == entry.strategyId
                        }
                        StrategyRow(
                            entry = entry,
                            busyPhase = busy?.phase,
                            actionsEnabled = actionsEnabled,
                            onAction = { onToggle(entry.strategyId) },
                        )
                    }
                }
                item(key = "web-note") {
                    WebEditingNote(showDetachedHint = shouldShowDetachedHint(content.entries))
                }
            }
        }
    }
}

/** « 2 actives sur 3 » + fraîcheur de la lecture (ou mention « état non vérifié »). */
@Composable
private fun StrategiesHeader(
    entries: List<PortfolioStrategyEntry>,
    syncedAt: Long?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = strategiesSummary(entries),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (syncedAt != null) {
            CacheTimestamp(
                syncedAt = syncedAt,
                modifier = Modifier.weight(1f, fill = false),
            )
        } else {
            Text(
                text = UNVERIFIED_STATE_LABEL,
                style = MaterialTheme.typography.labelSmall,
                color = LocalExtendedColors.current.onWarningContainer,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/**
 * Un lien portefeuille-stratégie : nom, pastille d'état, bouton d'action. L'état est lu par
 * TalkBack en un seul nœud (« Stratégie X, active ») annoncé quand il change ; le bouton est un
 * second nœud (« Mettre en pause la stratégie X »).
 */
@Composable
private fun StrategyRow(
    entry: PortfolioStrategyEntry,
    busyPhase: StrategyWritePhase?,
    actionsEnabled: Boolean,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayName = strategyDisplayName(entry.name, entry.strategyId)
    val statusDescription = strategyStatusDescription(displayName, entry.isActive)
    val actionDescription = if (busyPhase != null) {
        strategyBusyLabel(busyPhase)
    } else {
        strategyActionDescription(displayName, entry.isActive)
    }

    TradingCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics {
                        contentDescription = statusDescription
                        liveRegion = LiveRegionMode.Polite
                    },
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StrategyStatusPill(isActive = entry.isActive)
            }

            OutlinedButton(
                onClick = onAction,
                enabled = actionsEnabled,
                modifier = Modifier
                    .heightIn(min = MinTouchTarget)
                    .semantics { contentDescription = actionDescription },
            ) {
                if (busyPhase != null) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(IconSize.sm),
                        color = LocalContentColor.current,
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text(
                        text = strategyBusyLabel(busyPhase),
                        style = MaterialTheme.typography.labelLarge,
                    )
                } else {
                    Icon(
                        imageVector = if (entry.isActive) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(IconSize.sm),
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text(
                        text = linkActionLabel(entry.isActive),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

/** Pastille d'état : icône + texte (jamais la seule couleur), couleurs du thème. */
@Composable
private fun StrategyStatusPill(
    isActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val extended = LocalExtendedColors.current
    val containerColor = if (isActive) extended.successContainer else extended.warningContainer
    val contentColor = if (isActive) extended.onSuccessContainer else extended.onWarningContainer

    Row(
        modifier = modifier
            .background(color = containerColor, shape = CircleShape)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isActive) Icons.Filled.CheckCircle else Icons.Filled.PauseCircle,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(IconSize.sm),
        )
        Text(
            text = linkStatusLabel(isActive),
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1,
        )
    }
}

/** Rappel « Édition des stratégies : sur le web » (+ mise en garde sur les liens détachés). */
@Composable
private fun WebEditingNote(
    showDetachedHint: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Filled.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = Spacing.xxs)
                .size(IconSize.sm),
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                text = WEB_EDITING_NOTE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showDetachedHint) {
                Text(
                    text = DETACHED_LINK_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Previews (clair / sombre, police 130 %) ───────────────────────────────────

private fun previewState() = StrategiesUiState(
    portfolioId = "p1",
    content = StrategiesContent.Success(
        listOf(
            PortfolioStrategyEntry(strategyId = "s1", name = "Momentum US", isActive = true),
            PortfolioStrategyEntry(strategyId = "c2f4a9e0-1b7d-4e6a", name = null, isActive = false),
        ),
    ),
    syncedAt = System.currentTimeMillis(),
)

@Preview(showBackground = true, widthDp = 360)
@Preview(showBackground = true, widthDp = 360, fontScale = 1.3f)
@Composable
private fun StrategiesListPreviewLight() {
    TradingPlatformTheme(darkTheme = false) {
        StrategiesList(state = previewState(), onToggle = {}, onRetry = {})
    }
}

@Preview(showBackground = true, widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun StrategiesListPreviewDark() {
    TradingPlatformTheme(darkTheme = true) {
        StrategiesList(state = previewState(), onToggle = {}, onRetry = {})
    }
}
