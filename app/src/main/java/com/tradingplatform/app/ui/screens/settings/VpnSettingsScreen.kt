package com.tradingplatform.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradingplatform.app.ui.components.OfflineBadge
import com.tradingplatform.app.ui.components.OnlineBadge
import com.tradingplatform.app.ui.components.StatusBadge
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing
import com.tradingplatform.app.vpn.VpnState

/**
 * Settings screen for managing the WireGuard VPN tunnel.
 *
 * Displays the current VPN state, a human-readable description, and action buttons
 * to connect or disconnect the tunnel. The VPN is required for all server communications.
 *
 * Layout:
 * - Status card: badge + description of current state
 * - Action button: "Connecter" or "Déconnecter" depending on state
 * - Informational note about the VPN requirement
 *
 * Accessibility: all dynamic values have contentDescription for TalkBack.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnSettingsScreen(
    onNavigateToSecurity: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: VpnSettingsViewModel = hiltViewModel(),
) {
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Connexion VPN") })
        },
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            // ── Status card ───────────────────────────────────────────────────
            VpnStatusCard(vpnState = vpnState)

            // ── Action button ─────────────────────────────────────────────────
            VpnActionButton(
                vpnState = vpnState,
                onConnect = viewModel::connect,
                onDisconnect = viewModel::disconnect,
            )

            Spacer(modifier = Modifier.height(Spacing.sm))

            // ── Informational note ────────────────────────────────────────────
            VpnInfoNote()

            Spacer(modifier = Modifier.height(Spacing.sm))

            // ── Security section ──────────────────────────────────────────────
            Text(
                text = "Sécurité",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onNavigateToSecurity,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Accéder aux paramètres de sécurité" },
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.padding(end = Spacing.sm),
                )
                Text("Paramètres de sécurité")
            }
        }
    }
}

// ── Private composables ───────────────────────────────────────────────────────

@Composable
private fun VpnStatusCard(
    vpnState: VpnState,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = "État du tunnel",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── Status badge ──────────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                when (vpnState) {
                    is VpnState.Connected -> OnlineBadge()
                    is VpnState.Disconnected -> OfflineBadge()
                    is VpnState.Connecting -> StatusBadge(
                        text = "Connexion en cours...",
                        color = extendedColors.statusWarning,
                    )
                    is VpnState.Error -> StatusBadge(
                        text = "Erreur",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // ── State description ─────────────────────────────────────────────
            val description = when (vpnState) {
                is VpnState.Connected -> "Tunnel WireGuard actif. Toutes les communications avec le serveur passent par le tunnel chiffré."
                is VpnState.Disconnected -> "Tunnel WireGuard inactif. Les appels API sont bloqués jusqu'à la connexion."
                is VpnState.Connecting -> "Établissement du tunnel en cours..."
                is VpnState.Error -> "Erreur : ${vpnState.message}"
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = when (vpnState) {
                    is VpnState.Error -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.semantics {
                    contentDescription = "État VPN : $description"
                },
            )
        }
    }
}

@Composable
private fun VpnActionButton(
    vpnState: VpnState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isConnectedOrConnecting = vpnState is VpnState.Connected || vpnState is VpnState.Connecting

    if (isConnectedOrConnecting) {
        OutlinedButton(
            onClick = onDisconnect,
            modifier = modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Déconnecter le VPN" },
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text("Déconnecter")
        }
    } else {
        // Disconnected or Error — show connect button
        Button(
            onClick = onConnect,
            modifier = modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Connecter le VPN" },
        ) {
            Text("Connecter")
        }
    }
}

@Composable
private fun VpnInfoNote(
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Le VPN WireGuard est requis pour toutes les communications avec le serveur. " +
                "Sans tunnel actif, les données du portfolio et les cotations ne sont pas disponibles.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
