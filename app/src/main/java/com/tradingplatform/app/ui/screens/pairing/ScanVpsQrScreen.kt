package com.tradingplatform.app.ui.screens.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradingplatform.app.ui.components.ErrorBanner
import com.tradingplatform.app.ui.components.QrScannerView
import com.tradingplatform.app.ui.theme.Spacing

/**
 * Écran de scan du QR code VPS.
 *
 * - Idle → caméra active, on scanne le QR VPS
 * - VpsScanned → caméra pausée, card de confirmation (Continuer / Rescanner).
 *   Évite que la caméra du prochain écran ré-attrape le même QR encore dans le champ.
 * - DeviceScanned → caméra active, hint "QR Radxa détecté — scannez maintenant le QR VPS".
 * - BothScanned → navigation directe vers PairingProgressScreen.
 * - Error → banner, retry réinitialise l'état.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanVpsQrScreen(
    onNavigateToScanDevice: () -> Unit,
    onNavigateToProgress: () -> Unit,
    onBack: () -> Unit,
    viewModel: PairingViewModel = hiltViewModel(),
) {
    val step by viewModel.step.collectAsStateWithLifecycle()

    // La navigation vers DeviceScreen est déclenchée par l'utilisateur via la confirmation card
    // (pas automatiquement) — sinon la caméra du prochain écran relit immédiatement le QR VPS.
    LaunchedEffect(step) {
        if (step is PairingStep.BothScanned) {
            onNavigateToProgress()
        }
    }

    val isCameraPaused = step is PairingStep.VpsScanned ||
        step is PairingStep.BothScanned ||
        step is PairingStep.Error

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Scan QR VPS") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            viewModel.reset()
                            onBack()
                        },
                        modifier = Modifier.semantics {
                            contentDescription = "Retour"
                        },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            QrScannerView(
                onQrDetected = { raw -> viewModel.onVpsQrScanned(raw) },
                isPaused = isCameraPaused,
                modifier = Modifier.fillMaxSize(),
            )

            when (val current = step) {
                is PairingStep.VpsScanned -> {
                    VpsScannedConfirmCard(
                        onContinue = onNavigateToScanDevice,
                        onRescan = { viewModel.reset() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(bottom = Spacing.xl),
                    )
                }
                is PairingStep.DeviceScanned -> {
                    DeviceAlreadyScannedHintCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(bottom = Spacing.xl),
                    )
                }
                is PairingStep.Error -> {
                    ErrorBanner(
                        message = current.message,
                        onRetry = if (current.retryable) {
                            { viewModel.reset() }
                        } else {
                            null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter),
                    )
                }
                else -> {
                    InstructionOverlay(
                        text = "Scannez le QR affiché sur l'interface admin VPS",
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(bottom = Spacing.xl),
                    )
                }
            }
        }
    }
}

// ── Private composables ───────────────────────────────────────────────────────

@Composable
private fun InstructionOverlay(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(horizontal = Spacing.lg),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = Spacing.xs,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = "Le QR code est disponible dans : Admin > Edge Devices",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VpsScannedConfirmCard(
    onContinue: () -> Unit,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(horizontal = Spacing.lg),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = Spacing.sm,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = "QR VPS scanné",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = "Positionnez votre téléphone face à l'écran e-ink du Radxa, puis appuyez sur Continuer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(Spacing.lg))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                OutlinedButton(
                    onClick = onRescan,
                    modifier = Modifier.semantics { contentDescription = "Rescanner le QR VPS" },
                ) {
                    Text("Rescanner")
                }
                Button(
                    onClick = onContinue,
                    modifier = Modifier.semantics { contentDescription = "Continuer vers le scan du QR Radxa" },
                ) {
                    Text("Continuer")
                }
            }
        }
    }
}

@Composable
private fun DeviceAlreadyScannedHintCard(
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(horizontal = Spacing.lg),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = Spacing.xs,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "QR Radxa détecté ✓",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = "Scannez maintenant le QR VPS pour terminer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
