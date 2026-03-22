package com.tradingplatform.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tradingplatform.app.domain.model.WsConnectionState
import com.tradingplatform.app.ui.theme.LocalExtendedColors

/**
 * Indicateur discret de l'etat de la connexion WebSocket privee (F5).
 *
 * Affiche un petit dot colore (8dp) avec un label textuel optionnel.
 * Couleurs :
 * - Connected   : vert (statusOnline)
 * - Connecting  : orange (warning)
 * - Disconnected: rouge (statusOffline)
 * - Degraded    : orange (warning)
 *
 * La transition de couleur est animee (300ms) pour etre douce visuellement.
 * L'indicateur est accessible via TalkBack (contentDescription).
 *
 * Usage dans le TopAppBar du Dashboard :
 * ```kotlin
 * TopAppBar(
 *     title = { Text("Dashboard") },
 *     actions = {
 *         ConnectionStatusIndicator(state = wsState)
 *     }
 * )
 * ```
 */
@Composable
fun ConnectionStatusIndicator(
    state: WsConnectionState,
    modifier: Modifier = Modifier,
    showLabel: Boolean = false,
) {
    val extendedColors = LocalExtendedColors.current

    val dotColor by animateColorAsState(
        targetValue = when (state) {
            WsConnectionState.Connected -> extendedColors.statusOnline
            WsConnectionState.Connecting -> extendedColors.warning
            WsConnectionState.Disconnected -> extendedColors.statusOffline
            WsConnectionState.Degraded -> extendedColors.warning
        },
        animationSpec = tween(durationMillis = 300),
        label = "ws_status_color",
    )

    val description = when (state) {
        WsConnectionState.Connected -> "Temps reel actif"
        WsConnectionState.Connecting -> "Connexion en cours"
        WsConnectionState.Disconnected -> "Temps reel inactif"
        WsConnectionState.Degraded -> "Connexion instable"
    }

    Row(
        modifier = modifier.semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        if (showLabel) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
