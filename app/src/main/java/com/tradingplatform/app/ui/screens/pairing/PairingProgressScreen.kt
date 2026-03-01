package com.tradingplatform.app.ui.screens.pairing

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.tradingplatform.app.ui.theme.Spacing

/**
 * Screen shown while the pairing process is in progress.
 *
 * Auto-starts pairing via [PairingViewModel.startPairing] in a [LaunchedEffect].
 * Shows animated progress through the three stages:
 * 1. [PairingStep.SendingPin] — PIN being sent to Radxa
 * 2. [PairingStep.WaitingConfirmation] — polling for confirmation (up to 120 s)
 * 3. Outcome — navigates to [PairingDoneScreen]
 *
 * The startPairing() call is idempotent via the state guard in the ViewModel — calling
 * it when state is not [PairingStep.BothScanned] is a no-op.
 */
@Composable
fun PairingProgressScreen(
    onPairingComplete: () -> Unit,
    viewModel: PairingViewModel = hiltViewModel(),
) {
    val step by viewModel.step.collectAsStateWithLifecycle()

    // Auto-start pairing when this screen is first shown
    LaunchedEffect(Unit) {
        viewModel.startPairing()
    }

    // Navigate to done screen on terminal states
    LaunchedEffect(step) {
        when (step) {
            is PairingStep.Success,
            is PairingStep.Error -> onPairingComplete()
            else -> Unit
        }
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Pairing en cours",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(Spacing.xxl))

            // Animated content transitions between the two progress steps
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    (slideInVertically { it } + fadeIn())
                        .togetherWith(slideOutVertically { -it } + fadeOut())
                },
                contentAlignment = Alignment.Center,
                label = "PairingProgressAnimation",
            ) { currentStep ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    when (currentStep) {
                        is PairingStep.BothScanned,
                        is PairingStep.SendingPin -> SendingPinContent()

                        is PairingStep.WaitingConfirmation -> WaitingConfirmationContent()

                        else -> {
                            // Terminal states (Success/Error) — will navigate away immediately
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(48.dp)
                                    .semantics { contentDescription = "Chargement" },
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Step content composables ──────────────────────────────────────────────────

@Composable
private fun SendingPinContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = "Envoi du PIN en cours" },
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(Spacing.xl))
        Text(
            text = "Envoi du PIN au device...",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = "Connexion au device via le réseau local",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WaitingConfirmationContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Attente de confirmation du device" },
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Spacer(modifier = Modifier.height(Spacing.xl))
        Text(
            text = "Attente de confirmation...",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = "Le device configure le tunnel WireGuard (jusqu'à 2 min)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
