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
 * Screen to scan the VPS pairing QR code.
 *
 * Displays the camera viewfinder and instructions to scan the QR code shown on the
 * VPS admin interface. Delegates QR parsing to [PairingViewModel.onVpsQrScanned].
 *
 * Navigation:
 * - [PairingStep.VpsScanned] → [ScanDeviceQrScreen]
 * - [PairingStep.BothScanned] → [PairingProgressScreen] (device QR was already scanned)
 * - [PairingStep.Error] → stays on screen and shows [ErrorBanner] with retry option
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

    // Navigation side-effects driven by state
    LaunchedEffect(step) {
        when (step) {
            is PairingStep.VpsScanned -> onNavigateToScanDevice()
            is PairingStep.BothScanned -> onNavigateToProgress()
            else -> Unit
        }
    }

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
            // Camera viewfinder — occupies full screen
            QrScannerView(
                onQrDetected = { raw -> viewModel.onVpsQrScanned(raw) },
                modifier = Modifier.fillMaxSize(),
            )

            // Instruction overlay at the bottom of the camera feed
            InstructionOverlay(
                text = "Scannez le QR affiché sur l'interface admin VPS",
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = Spacing.xl),
            )

            // Error banner — shown on QR parse failure, allows retry
            if (step is PairingStep.Error) {
                val errorStep = step as PairingStep.Error
                ErrorBanner(
                    message = errorStep.message,
                    onRetry = if (errorStep.retryable) {
                        { viewModel.reset() }
                    } else {
                        null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter),
                )
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
