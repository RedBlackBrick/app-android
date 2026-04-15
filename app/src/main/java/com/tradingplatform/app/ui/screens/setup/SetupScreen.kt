package com.tradingplatform.app.ui.screens.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradingplatform.app.ui.components.QrScannerView
import com.tradingplatform.app.ui.theme.Spacing

/**
 * Initial onboarding screen — shown on first launch when setup is not yet completed.
 *
 * Flow:
 * 1. [SetupUiState.Scanning]   — full-screen camera viewfinder + instruction overlay
 * 2. [SetupUiState.Connecting] — progress indicator while WireGuard tunnel comes up
 * 3. [SetupUiState.Connected]  — brief confirmation, then [onSetupComplete] is called
 * 4. [SetupUiState.Error]      — error message + retry button to return to Scanning
 *
 * Navigation: [onSetupComplete] pops this screen and navigates to LoginScreen.
 */
@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Navigate as soon as Connected state is reached — single-shot side effect
    LaunchedEffect(uiState) {
        if (uiState is SetupUiState.Connected) {
            onSetupComplete()
        }
    }

    // Intercept system back during Connecting so the user can abort the VPN attempt
    // instead of being stuck on a spinner with no feedback. In Scanning state we let
    // back fall through to the system (standard root-screen behavior = exit app).
    BackHandler(enabled = uiState is SetupUiState.Connecting) {
        viewModel.cancelConnecting()
    }

    Scaffold(modifier = modifier) { innerPadding ->
        when (val state = uiState) {
            is SetupUiState.Scanning -> {
                ScanningContent(
                    onQrScanned = viewModel::onQrScanned,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            }

            is SetupUiState.Connecting -> {
                ConnectingContent(
                    onCancel = viewModel::cancelConnecting,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            }

            is SetupUiState.Connected -> {
                ConnectedContent(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            }

            is SetupUiState.Error -> {
                ErrorContent(
                    message = state.message,
                    onRetry = viewModel::retry,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            }
        }
    }
}

// ── Private composables ────────────────────────────────────────────────────────

@Composable
private fun ScanningContent(
    onQrScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        // Full-screen camera viewfinder
        QrScannerView(
            onQrDetected = onQrScanned,
            modifier = Modifier.fillMaxSize(),
        )

        // Instruction overlay at the bottom of the camera feed
        SetupInstructionOverlay(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = Spacing.xl),
        )
    }
}

@Composable
private fun SetupInstructionOverlay(
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
                text = "Configuration initiale",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = "Scannez le QR code affiché sur le panel web",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = "Paramètres → Lier mon mobile",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ConnectingContent(
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.semantics {
                contentDescription = "Connexion WireGuard en cours"
            },
        )
        Spacer(modifier = Modifier.height(Spacing.lg))
        Text(
            text = "Connexion VPN en cours...",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = "Établissement du tunnel WireGuard",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(Spacing.xxl))
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.semantics {
                contentDescription = "Annuler la connexion et revenir au scan"
            },
        ) {
            Text(
                text = "Annuler",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun ConnectedContent(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Connecté !",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics {
                contentDescription = "Connexion VPN établie, redirection en cours"
            },
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        Text(
            text = "Tunnel WireGuard établi avec succès",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Échec de la configuration",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(Spacing.xl))
        Button(onClick = onRetry) {
            Text("Réessayer")
        }
    }
}
