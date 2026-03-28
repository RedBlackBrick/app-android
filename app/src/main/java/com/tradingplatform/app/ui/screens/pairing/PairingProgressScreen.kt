package com.tradingplatform.app.ui.screens.pairing

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import com.tradingplatform.app.domain.model.DevicePairingInfo
import com.tradingplatform.app.ui.theme.IconSize
import com.tradingplatform.app.ui.theme.LocalExtendedColors
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
    val deviceInfo by viewModel.deviceInfo.collectAsStateWithLifecycle()

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

            Spacer(modifier = Modifier.height(Spacing.lg))

            // Device context card
            if (deviceInfo != null) {
                DeviceContextCard(deviceInfo = deviceInfo!!)
                Spacer(modifier = Modifier.height(Spacing.lg))
            }

            // Step indicators
            val currentStepIndex = when (step) {
                is PairingStep.BothScanned, is PairingStep.SendingPin -> 0
                is PairingStep.WaitingConfirmation -> 1
                is PairingStep.Success -> 2
                else -> 0
            }
            StepIndicator(
                steps = listOf("Envoi PIN", "Confirmation", "Termine"),
                currentStep = currentStepIndex,
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
                                    .size(IconSize.lg)
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
                .size(IconSize.lg)
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

// ── Step indicator ────────────────────────────────────────────────────────────

@Composable
private fun StepIndicator(
    steps: List<String>,
    currentStep: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEachIndexed { index, label ->
            val isActive = index <= currentStep
            val dotColor = if (isActive) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
            val textColor = if (isActive) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(Spacing.md)
                        .background(dotColor, CircleShape),
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor,
                )
            }

            if (index < steps.lastIndex) {
                Box(
                    modifier = Modifier
                        .width(Spacing.xxl)
                        .height(2.dp)
                        .background(
                            if (index < currentStep) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                )
            }
        }
    }
}

// ── Device context card ──────────────────────────────────────────────────────

@Composable
private fun DeviceContextCard(
    deviceInfo: DevicePairingInfo,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = extendedColors.cardSurface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Icon(
                imageVector = Icons.Default.DeveloperBoard,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Spacing.xl),
            )
            Column {
                Text(
                    text = deviceInfo.deviceId,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${deviceInfo.localIp}:${deviceInfo.port}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
