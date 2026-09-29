package com.tradingplatform.app.ui.screens.risk

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradingplatform.app.domain.model.RiskStatus
import com.tradingplatform.app.ui.common.DataState
import com.tradingplatform.app.ui.components.CacheTimestamp
import com.tradingplatform.app.ui.components.ConfirmActionSheet
import com.tradingplatform.app.ui.components.ErrorBanner
import com.tradingplatform.app.ui.components.PortfolioSwitcher
import com.tradingplatform.app.ui.components.SkeletonDashboardCard
import com.tradingplatform.app.ui.components.TradingCard
import com.tradingplatform.app.ui.theme.IconSize
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing
import com.tradingplatform.app.ui.theme.TradingPlatformTheme
import com.tradingplatform.app.ui.theme.asNumeric

/** Cible tactile minimale (Material : 48 dp). */
private val MinTouchTarget = 48.dp

/** Tags de test Compose de l'écran Risque. */
internal object RiskScreenTestTags {
    const val SUSPEND_BUTTON = "risk_suspend_button"
    const val KILL_SWITCH_CARD = "risk_kill_switch_card"
}

/**
 * Écran « Risque » du portefeuille actif : état du kill switch, violations non résolues, perte du
 * jour par rapport à la limite, drawdown courant. Écran poussé (bouton retour) ; sélecteur de
 * portefeuille dans la barre haute ; pull-to-refresh.
 *
 * Action unique : « Suspendre le trading (kill switch) », visible seulement si le kill switch est
 * inactif — garde d'écriture, confirmation avec motif obligatoire puis biométrie, relecture. Il
 * n'existe AUCUN bouton pour lever un kill switch : la levée se fait sur le web.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RiskScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RiskViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Message ponctuel : affiché puis consommé. Un nouveau message remplace l'ancien (clé de l'effet).
    LaunchedEffect(uiState.message) {
        val message = uiState.message
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.onMessageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Risque") },
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { innerPadding ->
        val risk = uiState.risk
        PullToRefreshBox(
            isRefreshing = risk.isRefreshing && !risk.isInitialLoading,
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
                val status = risk.value
                when {
                    risk.isInitialLoading -> {
                        SkeletonDashboardCard()
                        SkeletonDashboardCard()
                    }

                    status == null -> {
                        // Jamais chargé et en erreur : carte d'erreur avec « Réessayer ».
                        ErrorBanner(
                            message = risk.error ?: RISK_LOAD_DEFAULT_ERROR,
                            onRetry = { viewModel.refresh() },
                        )
                    }

                    else -> {
                        // Valeur périmée + dernier refresh en échec : la valeur reste affichée.
                        risk.error?.let { error ->
                            ErrorBanner(message = error, onRetry = { viewModel.refresh() })
                        }
                        RiskContent(
                            status = status,
                            risk = risk,
                            canOfferSuspend = uiState.canOfferSuspend,
                            isWriting = uiState.isWriting,
                            onSuspendClick = { viewModel.onSuspendClicked() },
                        )
                    }
                }
            }
        }
    }

    ConfirmActionSheet(
        action = uiState.pendingConfirmation,
        onDismiss = { viewModel.onConfirmationDismissed() },
        onConfirmed = { reason -> viewModel.onSuspendConfirmed(reason) },
    )
}

// ── Contenu ─────────────────────────────────────────────────────────────────────

@Composable
private fun RiskContent(
    status: RiskStatus,
    risk: DataState<RiskStatus>,
    canOfferSuspend: Boolean,
    isWriting: Boolean,
    onSuspendClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        KillSwitchCard(status = status)

        if (canOfferSuspend) {
            SuspendTradingAction(isWriting = isWriting, onClick = onSuspendClick)
        }

        TradingCard {
            Column(
                modifier = Modifier.padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                ViolationsRow(count = status.unresolvedViolations)
                HorizontalDivider(color = LocalExtendedColors.current.divider)
                DailyLossBlock(usage = status.dailyLossUsagePct)
                HorizontalDivider(color = LocalExtendedColors.current.divider)
                DrawdownRow(drawdown = status.drawdownCurrentPct)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            CacheTimestamp(syncedAt = risk.syncedAt)
            if (status.isPartial) {
                Text(
                    text = "Données partielles — certaines lectures du serveur ont échoué.",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalExtendedColors.current.onWarningContainer,
                )
            }
            Text(
                text = "Détail des violations, règles de risque et levée du kill switch : sur le web.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Carte d'état du kill switch. Actif : bandeau `errorContainer` (icône + texte, pas la seule couleur)
 * avec motif et « Levée : sur le web ». Inactif : sobre, avec une icône de coche (ou d'avertissement
 * si l'état n'a pas pu être vérifié).
 */
@Composable
private fun KillSwitchCard(
    status: RiskStatus,
    modifier: Modifier = Modifier,
) {
    val extended = LocalExtendedColors.current
    val headline = killSwitchHeadline(status)
    val reason = killSwitchReasonLabel(status.killSwitchReason)

    if (status.killSwitchActive) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .testTag(RiskScreenTestTags.KILL_SWITCH_CARD)
                .semantics(mergeDescendants = true) {
                    contentDescription = buildString {
                        append(headline)
                        if (reason != null) append(". ").append(reason)
                        append(". Levée : sur le web.")
                    }
                },
            color = MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Row(
                modifier = Modifier.padding(Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Icon(
                    imageVector = Icons.Filled.Block,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(IconSize.md),
                )
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        text = headline,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = "Les nouveaux ordres de ce portefeuille sont bloqués. " +
                            "Les ordres déjà ouverts et les sorties ne sont pas affectés.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    if (reason != null) {
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    Text(
                        text = "Levée : sur le web",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    } else {
        TradingCard(
            modifier = modifier.testTag(RiskScreenTestTags.KILL_SWITCH_CARD),
        ) {
            Row(
                modifier = Modifier
                    .padding(Spacing.lg)
                    .semantics(mergeDescendants = true) { contentDescription = headline },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Icon(
                    imageVector = if (status.isPartial) Icons.Filled.Warning else Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = if (status.isPartial) extended.warning else extended.success,
                    modifier = Modifier.size(IconSize.md),
                )
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
            }
        }
    }
}

/**
 * Bouton « Suspendre le trading (kill switch) » (destructif : contour et texte `error`) + rappel de
 * la portée. Désactivé pendant une écriture. Aucun équivalent pour lever un kill switch.
 */
@Composable
private fun SuspendTradingAction(
    isWriting: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val errorColor = MaterialTheme.colorScheme.error
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        OutlinedButton(
            onClick = onClick,
            enabled = !isWriting,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = errorColor),
            border = BorderStroke(width = 1.dp, color = errorColor.copy(alpha = if (isWriting) 0.38f else 1f)),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .testTag(RiskScreenTestTags.SUSPEND_BUTTON)
                .semantics {
                    contentDescription = if (isWriting) {
                        "Suspension du trading en cours"
                    } else {
                        "Suspendre le trading, kill switch. Ouvre une confirmation."
                    }
                },
        ) {
            if (isWriting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(IconSize.sm),
                    strokeWidth = Spacing.xxs,
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = "Suspension en cours…",
                    style = MaterialTheme.typography.labelLarge,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Block,
                    contentDescription = null,
                    modifier = Modifier.size(IconSize.sm),
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = "Suspendre le trading (kill switch)",
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Text(
            text = "Bloque les nouveaux ordres de ce portefeuille. Les ordres ouverts ne sont pas " +
                "annulés. La levée se fait sur le web.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Lignes d'indicateurs ────────────────────────────────────────────────────────

@Composable
private fun ViolationsRow(
    count: Int,
    modifier: Modifier = Modifier,
) {
    val level = violationsLevel(count)
    IndicatorRow(
        label = "Violations non résolues",
        value = violationsValue(count),
        valueColor = levelTextColor(level),
        spoken = violationsLabel(count),
        modifier = modifier,
    )
}

@Composable
private fun DrawdownRow(
    drawdown: Double?,
    modifier: Modifier = Modifier,
) {
    IndicatorRow(
        label = "Drawdown courant",
        value = drawdownValue(drawdown),
        valueColor = MaterialTheme.colorScheme.onSurface,
        spoken = drawdownSpoken(drawdown),
        modifier = modifier,
    )
}

/** Perte du jour : libellé + pourcentage de la limite + barre + niveau en toutes lettres. */
@Composable
private fun DailyLossBlock(
    usage: Double?,
    modifier: Modifier = Modifier,
) {
    val level = dailyLossLevel(usage)
    val barColor = if (level == RiskLevel.OK) MaterialTheme.colorScheme.primary else levelColor(level)
    val textColor = levelTextColor(level)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = dailyLossSpoken(usage) },
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = "Perte du jour (part de la limite)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = dailyLossValue(usage),
                style = MaterialTheme.typography.bodyMedium.asNumeric().copy(fontWeight = FontWeight.SemiBold),
                color = textColor,
                textAlign = TextAlign.End,
            )
        }
        if (level != RiskLevel.UNKNOWN) {
            LinearProgressIndicator(
                progress = { dailyLossProgress(usage) },
                modifier = Modifier.fillMaxWidth(),
                color = barColor,
                trackColor = barColor.copy(alpha = 0.15f),
                strokeCap = StrokeCap.Round,
            )
        }
        Text(
            text = riskLevelLabel(level),
            style = MaterialTheme.typography.labelSmall,
            color = if (level == RiskLevel.UNKNOWN || level == RiskLevel.OK) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                textColor
            },
        )
    }
}

@Composable
private fun IndicatorRow(
    label: String,
    value: String,
    valueColor: Color,
    spoken: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.asNumeric().copy(fontWeight = FontWeight.SemiBold),
            color = valueColor,
            textAlign = TextAlign.End,
        )
    }
}

/** Couleur graphique (barre) d'un niveau : warning / error du thème, jamais orange ou rouge en dur. */
@Composable
private fun levelColor(level: RiskLevel): Color = when (level) {
    RiskLevel.OK -> MaterialTheme.colorScheme.onSurface
    RiskLevel.WARNING -> LocalExtendedColors.current.warning
    RiskLevel.CRITICAL -> MaterialTheme.colorScheme.error
    RiskLevel.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Couleur de TEXTE d'un niveau : `onWarningContainer` (contraste suffisant, comme `CacheTimestamp`). */
@Composable
private fun levelTextColor(level: RiskLevel): Color = when (level) {
    RiskLevel.OK -> MaterialTheme.colorScheme.onSurface
    RiskLevel.WARNING -> LocalExtendedColors.current.onWarningContainer
    RiskLevel.CRITICAL -> MaterialTheme.colorScheme.error
    RiskLevel.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

// ── Previews ────────────────────────────────────────────────────────────────────

private val previewInactive = RiskStatus(
    killSwitchActive = false,
    killSwitchReason = null,
    unresolvedViolations = 3,
    dailyLossUsagePct = 0.85,
    drawdownCurrentPct = -0.124,
)

private val previewActive = previewInactive.copy(
    killSwitchActive = true,
    killSwitchReason = "Arrêt manuel depuis l'app",
    dailyLossUsagePct = 1.2,
)

@Composable
private fun RiskContentPreview(status: RiskStatus, canOfferSuspend: Boolean, darkTheme: Boolean) {
    TradingPlatformTheme(darkTheme = darkTheme) {
        Surface {
            Column(modifier = Modifier.padding(Spacing.lg)) {
                RiskContent(
                    status = status,
                    risk = DataState(value = status, syncedAt = System.currentTimeMillis()),
                    canOfferSuspend = canOfferSuspend,
                    isWriting = false,
                    onSuspendClick = {},
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun RiskContentInactiveLightPreview() {
    RiskContentPreview(status = previewInactive, canOfferSuspend = true, darkTheme = false)
}

@Preview(showBackground = true, widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RiskContentInactiveDarkPreview() {
    RiskContentPreview(status = previewInactive, canOfferSuspend = true, darkTheme = true)
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun RiskContentActiveLightPreview() {
    RiskContentPreview(status = previewActive, canOfferSuspend = false, darkTheme = false)
}

@Preview(showBackground = true, widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RiskContentActiveDarkPreview() {
    RiskContentPreview(status = previewActive, canOfferSuspend = false, darkTheme = true)
}
