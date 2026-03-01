package com.tradingplatform.app.ui.screens.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tradingplatform.app.ui.theme.Spacing

/**
 * Terminal screen of the pairing flow — shown after [PairingProgressScreen] completes.
 *
 * Receives the current [PairingStep] which must be either [PairingStep.Success] or
 * [PairingStep.Error]. Renders different UI depending on the outcome.
 *
 * Success:
 * - Checkmark icon
 * - "Pairing réussi !" heading
 * - "Terminer" button → [onFinish]
 *
 * Error (retryable=true):
 * - Error icon
 * - Error message
 * - "Réessayer" button → [onRetry] (resets state to Idle for re-scanning)
 *
 * Error (retryable=false):
 * - Error icon
 * - Error message
 * - "Fermer" button → [onFinish]
 */
@Composable
fun PairingDoneScreen(
    step: PairingStep,
    onRetry: () -> Unit,
    onFinish: () -> Unit,
    @Suppress("UNUSED_PARAMETER")
    viewModel: PairingViewModel = hiltViewModel(),
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (step) {
                is PairingStep.Success -> SuccessContent(onFinish = onFinish)
                is PairingStep.Error -> ErrorContent(
                    message = step.message,
                    retryable = step.retryable,
                    onRetry = onRetry,
                    onFinish = onFinish,
                )
                else -> {
                    // Should not happen — screen is only shown for Success/Error states
                }
            }
        }
    }
}

// ── Content composables ───────────────────────────────────────────────────────

@Composable
private fun SuccessContent(
    onFinish: () -> Unit,
) {
    Icon(
        imageVector = Icons.Default.CheckCircle,
        contentDescription = "Pairing réussi",
        modifier = Modifier
            .size(80.dp)
            .semantics { contentDescription = "Icône succès" },
        tint = MaterialTheme.colorScheme.primary,
    )

    Spacer(modifier = Modifier.height(Spacing.xl))

    Text(
        text = "Pairing réussi !",
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )

    Spacer(modifier = Modifier.height(Spacing.md))

    Text(
        text = "Le device est maintenant connecté au VPS via le tunnel WireGuard.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )

    Spacer(modifier = Modifier.height(Spacing.xxxl))

    Button(
        onClick = onFinish,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Terminer le pairing" },
    ) {
        Text(
            text = "Terminer",
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun ErrorContent(
    message: String,
    retryable: Boolean,
    onRetry: () -> Unit,
    onFinish: () -> Unit,
) {
    Icon(
        imageVector = Icons.Default.Warning,
        contentDescription = "Erreur de pairing",
        modifier = Modifier
            .size(80.dp)
            .semantics { contentDescription = "Icône erreur" },
        tint = MaterialTheme.colorScheme.error,
    )

    Spacer(modifier = Modifier.height(Spacing.xl))

    Text(
        text = "Pairing échoué",
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )

    Spacer(modifier = Modifier.height(Spacing.md))

    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )

    Spacer(modifier = Modifier.height(Spacing.xxxl))

    if (retryable) {
        Button(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Réessayer le pairing" },
        ) {
            Text(
                text = "Réessayer",
                style = MaterialTheme.typography.labelLarge,
            )
        }

        Spacer(modifier = Modifier.height(Spacing.sm))

        OutlinedButton(
            onClick = onFinish,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Fermer l'écran de pairing" },
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Text(
                text = "Fermer",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    } else {
        Button(
            onClick = onFinish,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Fermer l'écran de pairing" },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) {
            Text(
                text = "Fermer",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
