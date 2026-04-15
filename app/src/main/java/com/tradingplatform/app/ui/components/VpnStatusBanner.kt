package com.tradingplatform.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing

/**
 * Persistent banner shown when the WireGuard VPN tunnel is not connected or connecting.
 *
 * Slides in/out with animation. Displayed at the top of the main content area
 * in [AppNavGraph] to inform the user that API calls will fail.
 *
 * @param isDisconnected True if VPN is completely off.
 * @param isConnecting True if VPN is currently establishing connection.
 * @param onReconnect Optional callback to trigger a manual reconnection.
 */
@Composable
fun VpnStatusBanner(
    isDisconnected: Boolean,
    isConnecting: Boolean = false,
    onReconnect: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current
    val isVisible = isDisconnected || isConnecting

    AnimatedVisibility(
        visible = isVisible,
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = modifier,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = if (isConnecting) "Connexion au VPN..." else "VPN déconnecté"
                },
            color = if (isConnecting) MaterialTheme.colorScheme.secondaryContainer else extendedColors.warningContainer,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(Spacing.lg),
                        strokeWidth = Spacing.xs,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = extendedColors.onWarningContainer,
                        modifier = Modifier.size(Spacing.lg),
                    )
                }
                
                Text(
                    text = if (isConnecting) "Connexion au VPN en cours..." else "VPN déconnecté — données en cache",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isConnecting) MaterialTheme.colorScheme.onSecondaryContainer else extendedColors.onWarningContainer,
                    modifier = Modifier.weight(1f)
                )

                if (isDisconnected && onReconnect != null) {
                    IconButton(
                        onClick = onReconnect,
                        modifier = Modifier.size(Spacing.xl)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reconnecter le VPN",
                            tint = extendedColors.onWarningContainer
                        )
                    }
                }
            }
        }
    }
}
