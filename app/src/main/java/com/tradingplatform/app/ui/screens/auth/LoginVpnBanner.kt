package com.tradingplatform.app.ui.screens.auth

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.tradingplatform.app.ui.components.StatusBadge
import com.tradingplatform.app.ui.components.TradingCard
import com.tradingplatform.app.ui.theme.IconSize
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing
import timber.log.Timber

private const val OPEN_WIREGUARD_LABEL = "Ouvrir WireGuard"

/**
 * Bandeau d'état VPN au-dessus du formulaire de connexion ([model] : décision pure de
 * [loginVpnBanner]) :
 * - actif → pastille discrète « VPN actif » ;
 * - en cours → « Connexion au VPN… » + indicateur ;
 * - sinon → carte « VPN requis » : « Activer le VPN » (si [LoginVpnBannerModel.showActivate]) et
 *   « Ouvrir WireGuard » si l'app officielle est installée (`<queries>` du manifeste).
 *
 * Le bouton « Se connecter » du formulaire reste actif : ce bandeau informe, il ne bloque pas.
 */
@Composable
internal fun LoginVpnBanner(
    model: LoginVpnBannerModel,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (model.kind) {
        LoginVpnBannerKind.ACTIVE -> StatusBadge(
            text = model.title,
            color = LocalExtendedColors.current.statusOnline,
            modifier = modifier,
        )

        LoginVpnBannerKind.CONNECTING -> ConnectingPill(title = model.title, modifier = modifier)

        LoginVpnBannerKind.REQUIRED -> VpnRequiredCard(
            model = model,
            onActivate = onActivate,
            modifier = modifier,
        )
    }
}

@Composable
private fun ConnectingPill(
    title: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "Connexion au VPN en cours"
        },
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = CircleShape,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(Spacing.lg),
                strokeWidth = Spacing.xs,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun VpnRequiredCard(
    model: LoginVpnBannerModel,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Détecte l'app WireGuard officielle (visible grâce à `<queries>` sur Android 11+).
    val wireGuardIntent = remember(context) {
        context.packageManager.getLaunchIntentForPackage(WIREGUARD_PACKAGE)
    }

    TradingCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = LocalExtendedColors.current.warning,
                    modifier = Modifier.size(IconSize.md),
                )
                Text(
                    text = model.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            model.detail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (model.consentDenied) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            if (model.showActivate) {
                Button(
                    onClick = onActivate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Spacing.xxxl),
                ) {
                    Text(
                        text = model.activateLabel,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            if (wireGuardIntent != null) {
                OutlinedButton(
                    onClick = {
                        try {
                            context.startActivity(wireGuardIntent)
                        } catch (e: ActivityNotFoundException) {
                            Timber.w(e, "WireGuard app could not be launched")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Spacing.xxxl),
                ) {
                    Text(
                        text = OPEN_WIREGUARD_LABEL,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}
