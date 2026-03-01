package com.tradingplatform.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.tradingplatform.app.ui.theme.Spacing

/**
 * Banner d'erreur Material 3 ancré en bas de l'écran.
 *
 * Affiche [message] sur fond [errorContainer]. Bouton "Réessayer" affiché si [onRetry] != null,
 * icône de fermeture si [onDismiss] != null.
 *
 * Usage :
 * ```kotlin
 * Box(modifier = Modifier.fillMaxSize()) {
 *     ScreenContent()
 *     if (error != null) {
 *         ErrorBanner(
 *             message = error,
 *             onRetry = { viewModel.retry() },
 *             onDismiss = { viewModel.clearError() },
 *             modifier = Modifier.align(Alignment.BottomCenter),
 *         )
 *     }
 * }
 * ```
 */
@Composable
fun ErrorBanner(
    message: String,
    onDismiss: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Erreur : $message" },
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = Spacing.xs,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )

            if (onRetry != null) {
                TextButton(onClick = onRetry) {
                    Text(
                        text = "Réessayer",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            if (onDismiss != null) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Fermer le message d'erreur",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}
