package com.tradingplatform.app.ui.screens.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradingplatform.app.domain.model.ChannelPreferences
import com.tradingplatform.app.domain.model.NotifCategory
import com.tradingplatform.app.domain.model.NotificationPreferences
import com.tradingplatform.app.domain.model.QuietHours
import com.tradingplatform.app.domain.model.RiskAlertThresholds
import com.tradingplatform.app.ui.common.DataState
import com.tradingplatform.app.ui.components.CacheTimestamp
import com.tradingplatform.app.ui.components.ConfirmActionSheet
import com.tradingplatform.app.ui.components.ErrorBanner
import com.tradingplatform.app.ui.components.SkeletonDashboardCard
import com.tradingplatform.app.ui.components.TradingCard
import com.tradingplatform.app.ui.theme.IconSize
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing
import com.tradingplatform.app.ui.theme.TradingPlatformTheme
import com.tradingplatform.app.ui.theme.asNumeric

/** Cible tactile minimale (Material : 48 dp). */
private val MinTouchTarget = 48.dp

/** Préfixe des tags de test des interrupteurs : `notif_push_switch_<wireKey>`. */
internal const val NOTIF_PUSH_SWITCH_TAG_PREFIX = "notif_push_switch_"

/**
 * Écran « Notifications » (Réglages → Notifications) : un interrupteur « Notification push » par
 * catégorie (Signaux de stratégie, Alertes de risque, Système). Chaque changement passe par la
 * garde d'écriture, la confirmation biométrique, l'écriture puis la RELECTURE : l'interrupteur
 * affiche toujours l'état relu du serveur, jamais un état espéré.
 *
 * Heures calmes et seuils d'alerte de risque sont affichés en lecture seule : leur réglage se fait
 * sur le web.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationPrefsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NotificationPrefsViewModel = hiltViewModel(),
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
                title = { Text("Notifications") },
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { innerPadding ->
        val prefsState = uiState.prefs
        PullToRefreshBox(
            isRefreshing = prefsState.isRefreshing && !prefsState.isInitialLoading,
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
                val prefs = prefsState.value
                when {
                    prefsState.isInitialLoading -> {
                        SkeletonDashboardCard()
                        SkeletonDashboardCard()
                        SkeletonDashboardCard()
                    }

                    prefs == null -> {
                        // Jamais chargé et en erreur : carte d'erreur avec « Réessayer ».
                        ErrorBanner(
                            message = prefsState.error ?: PREFS_LOAD_DEFAULT_ERROR,
                            onRetry = { viewModel.refresh() },
                        )
                    }

                    else -> {
                        // Valeur périmée + dernier refresh en échec : la valeur reste affichée.
                        prefsState.error?.let { error ->
                            ErrorBanner(message = error, onRetry = { viewModel.refresh() })
                        }
                        PrefsContent(
                            prefs = prefs,
                            state = prefsState,
                            savingCategory = uiState.savingCategory,
                            onToggle = { category, enabled -> viewModel.onPushToggleRequested(category, enabled) },
                        )
                    }
                }
            }
        }
    }

    ConfirmActionSheet(
        action = uiState.pendingConfirmation,
        onDismiss = { viewModel.onChangeDismissed() },
        onConfirmed = { viewModel.onChangeConfirmed() },
    )
}

// ── Contenu ─────────────────────────────────────────────────────────────────────

@Composable
private fun PrefsContent(
    prefs: NotificationPreferences,
    state: DataState<NotificationPreferences>,
    savingCategory: NotifCategory?,
    onToggle: (NotifCategory, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dividerColor = LocalExtendedColors.current.divider
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Text(
            text = "Choisissez les catégories envoyées en notification push. " +
                "Les autres canaux se règlent sur le web.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        TradingCard {
            Column {
                NotifCategory.entries.forEachIndexed { index, category ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = dividerColor,
                            modifier = Modifier.padding(horizontal = Spacing.lg),
                        )
                    }
                    PushCategoryRow(
                        category = category,
                        pushEnabled = prefs.isPushEnabled(category),
                        isSaving = savingCategory == category,
                        interactive = savingCategory == null,
                        onToggle = { enabled -> onToggle(category, enabled) },
                    )
                }
            }
        }

        ReadOnlyCard(title = "Heures calmes") {
            Text(
                text = quietHoursSummary(prefs.quietHours),
                style = MaterialTheme.typography.bodyMedium.asNumeric(),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        ReadOnlyCard(title = "Seuils d'alerte de risque") {
            val lines = thresholdLines(prefs.riskAlertThresholds)
            if (lines.isEmpty()) {
                Text(
                    text = "Aucun seuil configuré",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                lines.forEach { (label, value) -> ValueRow(label = label, value = value) }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            CacheTimestamp(syncedAt = state.syncedAt)
            Text(
                text = "Réglages avancés : sur le web.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Une catégorie : titre, description, puis la ligne « Notification push » entière cliquable
 * (cible ≥ 48 dp, rôle Switch pour TalkBack). L'interrupteur reflète [pushEnabled], l'état RELU :
 * un tap ne le fait pas bouger, il ouvre la confirmation.
 */
@Composable
private fun PushCategoryRow(
    category: NotifCategory,
    pushEnabled: Boolean,
    isSaving: Boolean,
    interactive: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = categoryTitle(category)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = categoryDescription(category),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .toggleable(
                    value = pushEnabled,
                    enabled = interactive,
                    role = Role.Switch,
                    onValueChange = onToggle,
                )
                .testTag(NOTIF_PUSH_SWITCH_TAG_PREFIX + category.wireKey)
                .semantics {
                    contentDescription = "Notification push, $title"
                    stateDescription = pushStateLabel(pushEnabled)
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = "Notification push",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(IconSize.sm),
                    strokeWidth = Spacing.xxs,
                )
            }
            // Interrupteur non interactif par lui-même : c'est la ligne (toggleable) qui porte le tap.
            Switch(
                checked = pushEnabled,
                onCheckedChange = null,
                enabled = interactive,
            )
        }
    }
}

/** Carte d'information en lecture seule (titre + contenu). */
@Composable
private fun ReadOnlyCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    TradingCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            content()
        }
    }
}

/** Ligne « libellé → valeur » (valeur en mono + chiffres tabulaires, alignée à droite). */
@Composable
private fun ValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "$label : $value" },
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
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}

// ── Previews ────────────────────────────────────────────────────────────────────

private val previewPrefs = NotificationPreferences(
    categories = mapOf(
        NotifCategory.STRATEGY_SIGNAL to ChannelPreferences(inApp = true, push = true, email = false),
        NotifCategory.RISK_ALERT to ChannelPreferences(inApp = true, push = true, email = true),
        NotifCategory.SYSTEM to ChannelPreferences(inApp = true, push = false, email = true),
    ),
    quietHours = QuietHours(enabled = true, start = "22:00", end = "08:00"),
    riskAlertThresholds = RiskAlertThresholds(drawdownWarnPct = 0.10, suppressedSignalsPerDay = 25),
)

@Composable
private fun PrefsContentPreview(darkTheme: Boolean) {
    TradingPlatformTheme(darkTheme = darkTheme) {
        Surface {
            Column(modifier = Modifier.padding(Spacing.lg)) {
                PrefsContent(
                    prefs = previewPrefs,
                    state = DataState(value = previewPrefs, syncedAt = System.currentTimeMillis()),
                    savingCategory = null,
                    onToggle = { _, _ -> },
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun PrefsContentLightPreview() {
    PrefsContentPreview(darkTheme = false)
}

@Preview(showBackground = true, widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PrefsContentDarkPreview() {
    PrefsContentPreview(darkTheme = true)
}
