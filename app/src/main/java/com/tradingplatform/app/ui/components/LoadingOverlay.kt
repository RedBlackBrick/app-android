package com.tradingplatform.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Overlay semi-transparent affiché sur l'écran entier pendant les chargements.
 *
 * Bloque toutes les interactions tactiles (consomme les touch events) et affiche
 * un [CircularProgressIndicator] centré.
 *
 * Usage :
 * ```kotlin
 * Box {
 *     ScreenContent()
 *     if (uiState is Loading) LoadingOverlay()
 * }
 * ```
 */
@Composable
fun LoadingOverlay(modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
            // Consomme tous les clics — empêche l'interaction avec le contenu sous-jacent
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {},
            )
            .semantics { contentDescription = "Chargement en cours" },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
