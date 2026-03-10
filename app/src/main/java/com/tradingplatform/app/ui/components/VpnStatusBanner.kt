package com.tradingplatform.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing

/**
 * Persistent banner shown when the WireGuard VPN tunnel is not connected.
 *
 * Slides in/out with animation. Displayed at the top of the main content area
 * in [AppNavGraph] to inform the user that API calls will fail.
 */
@Composable
fun VpnStatusBanner(
    isDisconnected: Boolean,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current

    AnimatedVisibility(
        visible = isDisconnected,
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = modifier,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "VPN déconnecté — les données ne sont pas actualisées"
                },
            color = extendedColors.warningContainer,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = extendedColors.onWarningContainer,
                    modifier = Modifier.size(Spacing.lg),
                )
                Text(
                    text = "VPN déconnecté — données en cache",
                    style = MaterialTheme.typography.labelMedium,
                    color = extendedColors.onWarningContainer,
                )
            }
        }
    }
}
