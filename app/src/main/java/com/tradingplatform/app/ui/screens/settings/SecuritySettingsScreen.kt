package com.tradingplatform.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradingplatform.app.ui.components.LoadingOverlay
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing

/**
 * Settings screen for displaying device security status.
 *
 * Three sections:
 * 1. Biometrics — availability and inactivity lock description.
 * 2. Device integrity — root detection result with appropriate visual indicator.
 * 3. Theme — informational note about the fixed theme.
 *
 * Accessibility: status rows carry contentDescription for TalkBack.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecuritySettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SecuritySettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Sécurité") })
        },
        modifier = modifier,
    ) { innerPadding ->
        if (uiState.isLoading) {
            LoadingOverlay()
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            // ── Biometrics section ────────────────────────────────────────────
            BiometricsSection(isBiometricAvailable = uiState.isBiometricAvailable)

            // ── Device integrity section ──────────────────────────────────────
            DeviceIntegritySection(isRooted = uiState.isRooted)

            // ── Theme section ─────────────────────────────────────────────────
            ThemeSection()
        }
    }
}

// ── Section composables ───────────────────────────────────────────────────────

@Composable
private fun BiometricsSection(
    isBiometricAvailable: Boolean,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current

    SettingsCard(
        title = "Biométrie",
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = if (isBiometricAvailable)
                        "Biométrie disponible et configurée"
                    else
                        "Biométrie non disponible sur cet appareil"
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = if (isBiometricAvailable)
                    extendedColors.success
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Spacing.xl),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    text = if (isBiometricAvailable)
                        "Biométrie disponible"
                    else
                        "Biométrie non disponible",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isBiometricAvailable)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isBiometricAvailable) {
                    Text(
                        text = "Verrou automatique après 5 minutes d'inactivité",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "Configurez une empreinte ou reconnaissance faciale dans les paramètres de l'appareil pour activer le verrou biométrique.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceIntegritySection(
    isRooted: Boolean,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current

    SettingsCard(
        title = "Intégrité de l'appareil",
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = if (isRooted)
                        "Avertissement : Device rooté détecté, sécurité réduite"
                    else
                        "Appareil non rooté, intégrité vérifiée"
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(
                imageVector = if (isRooted) Icons.Default.Warning else Icons.Default.CheckCircle,
                contentDescription = null,
                tint = if (isRooted)
                    extendedColors.warning
                else
                    extendedColors.success,
                modifier = Modifier.size(Spacing.xl),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    text = if (isRooted)
                        "Device rooté détecté — sécurité réduite"
                    else
                        "Appareil non rooté",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isRooted)
                        extendedColors.warning
                    else
                        MaterialTheme.colorScheme.onSurface,
                )
                if (isRooted) {
                    Text(
                        text = "Un appareil rooté réduit les garanties de sécurité. " +
                            "Les clés cryptographiques et les tokens peuvent être exposés.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "Aucun indicateur de root détecté sur cet appareil.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeSection(
    modifier: Modifier = Modifier,
) {
    SettingsCard(
        title = "Thème",
        modifier = modifier,
    ) {
        Text(
            text = "Thème fixe — DynamicColor désactivé pour cohérence avec l'interface web",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "L'application utilise un thème indigo fixe correspondant à la plateforme web de trading. " +
                "Material You (couleurs dynamiques) est intentionnellement désactivé.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}

// ── Reusable card wrapper ─────────────────────────────────────────────────────

/**
 * Card wrapper used by all settings sections.
 * Renders a [title] label above a [HorizontalDivider] and the provided [content].
 */
@Composable
private fun SettingsCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline,
            )
            content()
        }
    }
}
