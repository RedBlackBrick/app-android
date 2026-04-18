package com.tradingplatform.app.ui.screens.pairing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradingplatform.app.ui.components.ErrorBanner
import com.tradingplatform.app.ui.components.QrScannerView
import com.tradingplatform.app.ui.theme.Spacing

/**
 * Écran de scan du QR code Radxa.
 *
 * - VpsScanned (état d'arrivée) → caméra active, scanne le QR device.
 * - BothScanned → navigation vers PairingProgressScreen.
 * - Error → banner, retry ramène l'utilisateur à [ScanVpsQrScreen] (l'état VPS a été reset).
 *
 * La caméra est pausée dès qu'un état terminal est atteint (BothScanned / Error) pour éviter
 * toute relecture parasite du même QR avant la navigation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanDeviceQrScreen(
    onNavigateToProgress: () -> Unit,
    onBack: () -> Unit,
    viewModel: PairingViewModel = hiltViewModel(),
) {
    val step by viewModel.step.collectAsStateWithLifecycle()

    LaunchedEffect(step) {
        if (step is PairingStep.BothScanned) {
            onNavigateToProgress()
        }
    }

    val isCameraPaused = step is PairingStep.BothScanned ||
        step is PairingStep.Error

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Scan QR Device") },
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
                onQrDetected = { raw -> viewModel.onDeviceQrScanned(raw) },
                isPaused = isCameraPaused,
                modifier = Modifier.fillMaxSize(),
            )

            when (val current = step) {
                is PairingStep.Error -> {
                    // retry reset l'état et ramène à l'écran VPS pour repartir d'un état propre
                    ErrorBanner(
                        message = current.message,
                        onRetry = if (current.retryable) {
                            {
                                viewModel.reset()
                                onBack()
                            }
                        } else {
                            null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter),
                    )
                }
                else -> {
                    DeviceInstructionOverlay(
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
private fun DeviceInstructionOverlay(
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
                text = "Scannez le QR affiché sur l'écran e-ink du device Radxa",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = "Le QR code apparaît sur l'écran du device au démarrage du pairing",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
