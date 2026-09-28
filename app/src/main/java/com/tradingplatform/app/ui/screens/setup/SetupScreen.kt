package com.tradingplatform.app.ui.screens.setup

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradingplatform.app.ui.components.QrScannerView
import com.tradingplatform.app.ui.theme.Spacing
import timber.log.Timber

/**
 * Initial onboarding screen — shown on first launch when setup is not yet completed.
 *
 * Flow:
 * 1. [SetupUiState.Scanning]   — full-screen camera viewfinder + instruction overlay
 * 2. [SetupUiState.Connecting] — progress indicator while WireGuard tunnel comes up
 * 3. [SetupUiState.Connected]  — brief confirmation, then [onSetupComplete] is called
 * 4. [SetupUiState.Error]      — error message + retry button to return to Scanning
 * 5. [SetupUiState.VpnConsentRequired] — the system VPN consent dialog is launched via
 *    `rememberLauncherForActivityResult(StartActivityForResult())`; RESULT_OK retries the tunnel
 * 6. [SetupUiState.VpnConsentDenied]   — explicit message + retry button that re-asks consent
 *
 * Navigation: [onSetupComplete] pops this screen and navigates to LoginScreen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // System VPN consent dialog (VpnService.prepare). RESULT_OK = granted.
    val vpnConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.onVpnConsentResult(granted = result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(uiState) {
        val state = uiState
        if (state is SetupUiState.Connected) {
            onSetupComplete()
        }
        // `launched` lives in the ViewModel: a recomposition / config change while the
        // dialog is on screen does not launch it a second time.
        if (state is SetupUiState.VpnConsentRequired && !state.launched) {
            launchVpnConsent(
                intent = viewModel.vpnConsentIntent(),
                launch = { vpnConsentLauncher.launch(it) },
                onLaunched = viewModel::onVpnConsentLaunched,
                onResult = viewModel::onVpnConsentResult,
            )
        }
    }

    BackHandler(enabled = uiState is SetupUiState.Connecting) {
        viewModel.cancelConnecting()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            if (uiState is SetupUiState.Scanning ||
                uiState is SetupUiState.Error ||
                uiState is SetupUiState.VpnConsentDenied
            ) {
                TopAppBar(
                    title = { Text(text = "Configuration initiale") },
                    actions = {
                        IconButton(
                            onClick = { (context as? Activity)?.finish() },
                            modifier = Modifier.semantics {
                                contentDescription = "Quitter l'application"
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
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

            is SetupUiState.VpnConsentRequired -> {
                VpnConsentPendingContent(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            }

            is SetupUiState.VpnConsentDenied -> {
                ErrorContent(
                    title = "Autorisation VPN requise",
                    message = state.message,
                    onRetry = viewModel::retryVpnConsent,
                    retryContentDescription = "Réessayer : redemander l'autorisation VPN",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            }
        }
    }
}

/**
 * Launches the system VPN consent dialog, or reports "granted" right away when
 * `VpnService.prepare` no longer needs it. A ROM without the VpnDialogs activity
 * (ActivityNotFoundException) is reported as a denial so that the user gets the
 * explicit message instead of a crash. Shared with VpnSettingsScreen.
 */
internal fun launchVpnConsent(
    intent: Intent?,
    launch: (Intent) -> Unit,
    onLaunched: () -> Unit,
    onResult: (granted: Boolean) -> Unit,
) {
    if (intent == null) {
        onResult(true)
        return
    }
    try {
        onLaunched()
        launch(intent)
    } catch (e: ActivityNotFoundException) {
        Timber.w(e, "VPN consent dialog unavailable on this device")
        onResult(false)
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
private fun VpnConsentPendingContent(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Autorisation VPN",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        Text(
            text = "Android demande d'autoriser l'application à créer le tunnel VPN. " +
                "Acceptez la demande de connexion pour continuer.",
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
    title: String = "Échec de la configuration",
    retryContentDescription: String? = null,
) {
    Column(
        modifier = modifier.padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
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
        Button(
            onClick = onRetry,
            modifier = if (retryContentDescription != null) {
                Modifier.semantics { contentDescription = retryContentDescription }
            } else {
                Modifier
            },
        ) {
            Text("Réessayer")
        }
    }
}
