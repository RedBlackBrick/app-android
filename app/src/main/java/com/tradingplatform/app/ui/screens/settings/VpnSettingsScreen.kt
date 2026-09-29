package com.tradingplatform.app.ui.screens.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.LaunchedEffect
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
import com.tradingplatform.app.ui.components.VpnTunnelDiagram
import com.tradingplatform.app.ui.screens.setup.VPN_CONSENT_DENIED_MESSAGE
import com.tradingplatform.app.ui.screens.setup.launchVpnConsent
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
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {},
    viewModel: VpnSettingsViewModel = hiltViewModel(),
) {
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val consentState by viewModel.consentState.collectAsStateWithLifecycle()

    // System VPN consent dialog (VpnService.prepare). RESULT_OK = granted.
    val vpnConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.onVpnConsentResult(granted = result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(consentState) {
        if (consentState == VpnConsentUiState.Required) {
            launchVpnConsent(
                intent = viewModel.vpnConsentIntent(),
                launch = { vpnConsentLauncher.launch(it) },
                onLaunched = viewModel::onVpnConsentLaunched,
                onResult = viewModel::onVpnConsentResult,
            )
        }
    }
    val consentDenied = consentState == VpnConsentUiState.Denied

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connexion VPN") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Retour",
                        )
                    }
                },
            )
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
            VpnStatusCard(vpnState = vpnState, consentDenied = consentDenied)

            // ── Action button ─────────────────────────────────────────────────
            VpnActionButton(
                vpnState = vpnState,
                consentDenied = consentDenied,
                onConnect = viewModel::connect,
                onDisconnect = viewModel::disconnect,
                onRequestConsent = viewModel::requestVpnConsent,
            )

            Spacer(modifier = Modifier.height(Spacing.sm))

            // ── Informational note ────────────────────────────────────────────
            VpnInfoNote()
        }
    }
}

// ── Private composables ───────────────────────────────────────────────────────

@Composable
private fun VpnStatusCard(
    vpnState: VpnState,
    consentDenied: Boolean,
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

            // ── Schéma animé téléphone — tunnel — serveur (état affiché) ──────
            VpnTunnelDiagram(state = vpnState)

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
                    is VpnState.SystemVpnActive -> StatusBadge(
                        text = "VPN système",
                        color = extendedColors.info,
                    )
                    is VpnState.Error -> StatusBadge(
                        text = "Erreur",
                        color = MaterialTheme.colorScheme.error,
                    )
                    // Tunnel down like Disconnected, but Android's consent is needed first.
                    is VpnState.ConsentRequired -> StatusBadge(
                        text = "Autorisation requise",
                        color = if (consentDenied) MaterialTheme.colorScheme.error else extendedColors.statusWarning,
                    )
                }
            }

            // ── State description ─────────────────────────────────────────────
            val description = when (vpnState) {
                is VpnState.Connected -> "Tunnel WireGuard actif. Toutes les communications avec le serveur passent par le tunnel chiffré."
                is VpnState.Disconnected -> "Tunnel WireGuard inactif. Les appels API sont bloqués jusqu'à la connexion."
                is VpnState.Connecting -> "Établissement du tunnel en cours..."
                is VpnState.SystemVpnActive -> SYSTEM_VPN_ACTIVE_DESCRIPTION
                is VpnState.Error -> "Erreur : ${vpnState.message}"
                is VpnState.ConsentRequired ->
                    if (consentDenied) VPN_CONSENT_DENIED_MESSAGE else VPN_CONSENT_REQUIRED_DESCRIPTION
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    vpnState is VpnState.Error -> MaterialTheme.colorScheme.error
                    vpnState is VpnState.ConsentRequired && consentDenied -> MaterialTheme.colorScheme.error
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
    consentDenied: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRequestConsent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (vpnState) {
        is VpnState.ConsentRequired -> Button(
            onClick = onRequestConsent,
            modifier = modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Demander l'autorisation VPN à Android" },
        ) {
            Text(if (consentDenied) "Réessayer" else "Autoriser le VPN")
        }

        // Un VPN tiers porte le trafic (D6) : le tunnel intégré n'est pas pilotable ici —
        // Android n'autorise qu'un VPN à la fois, « Connecter » révoquerait l'autre app.
        is VpnState.SystemVpnActive -> OutlinedButton(
            onClick = {},
            enabled = false,
            modifier = modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Actions VPN indisponibles : VPN système actif" },
        ) {
            Text("Géré par l'app VPN externe")
        }

        is VpnState.Connected, is VpnState.Connecting -> OutlinedButton(
            // Sûr pendant Connecting : WireGuardManager sérialise connect/disconnect (Mutex)
            // et une déconnexion demandée pendant setState(UP) redescend le tunnel.
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

        is VpnState.Disconnected, is VpnState.Error -> Button(
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

internal const val VPN_CONSENT_REQUIRED_DESCRIPTION =
    "Android doit autoriser l'application à créer le tunnel VPN avant la connexion."

internal const val SYSTEM_VPN_ACTIVE_DESCRIPTION =
    "VPN système actif (tunnel externe) — le tunnel WireGuard intégré n'est pas utilisé"
