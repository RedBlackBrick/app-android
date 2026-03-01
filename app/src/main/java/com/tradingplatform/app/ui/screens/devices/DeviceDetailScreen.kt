package com.tradingplatform.app.ui.screens.devices

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.tradingplatform.app.domain.model.Device
import com.tradingplatform.app.domain.model.DeviceStatus
import com.tradingplatform.app.ui.components.CacheTimestamp
import com.tradingplatform.app.ui.components.ErrorBanner
import com.tradingplatform.app.ui.components.LoadingOverlay
import com.tradingplatform.app.ui.components.OfflineBadge
import com.tradingplatform.app.ui.components.OnlineBadge
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing

/**
 * Ecran de détail d'un device (admin uniquement).
 *
 * Affiche le nom du device, son statut avec badge coloré, son IP WireGuard et
 * l'horodatage du dernier heartbeat. Bouton retour dans la TopAppBar.
 *
 * Le chargement est déclenché dans [LaunchedEffect] avec [deviceId] comme clé
 * — se relance si l'id change (navigation vers un autre device).
 *
 * @param deviceId identifiant du device à afficher (depuis navigation args)
 * @param onNavigateBack callback pour revenir à l'écran précédent
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(
    deviceId: String,
    onNavigateBack: () -> Unit,
    viewModel: DeviceDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Charge le device dès que l'écran est affiché (ou si deviceId change)
    LaunchedEffect(deviceId) {
        viewModel.loadDevice(deviceId)
    }

    val screenTitle = when (val state = uiState) {
        is DeviceDetailUiState.Success -> state.device.name
        else -> "Device"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = screenTitle)
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Retour à la liste des devices",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val state = uiState) {
                is DeviceDetailUiState.Loading -> {
                    // LoadingOverlay affiché par-dessus
                }

                is DeviceDetailUiState.Success -> {
                    DeviceDetailContent(
                        device = state.device,
                        syncedAt = state.syncedAt,
                    )
                }

                is DeviceDetailUiState.Error -> {
                    // Message d'erreur centré
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(Spacing.lg),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(modifier = Modifier.height(Spacing.xxxl))
                        Text(
                            text = "Impossible de charger les informations du device",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // LoadingOverlay positionné par-dessus tout le contenu
            if (uiState is DeviceDetailUiState.Loading) {
                LoadingOverlay()
            }

            // ErrorBanner ancré en bas avec option de retry
            if (uiState is DeviceDetailUiState.Error) {
                val errorMessage = (uiState as DeviceDetailUiState.Error).message
                ErrorBanner(
                    message = errorMessage,
                    onRetry = { viewModel.refresh(deviceId) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

// ── Contenu détail ─────────────────────────────────────────────────────────────

@Composable
private fun DeviceDetailContent(
    device: Device,
    syncedAt: Long,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.lg),
    ) {
        // En-tête : nom + badge statut
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = extendedColors.cardSurface,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = Spacing.xs),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.lg),
            ) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics {
                        contentDescription = "Nom du device : ${device.name}"
                    },
                )

                Spacer(modifier = Modifier.height(Spacing.sm))

                when (device.status) {
                    DeviceStatus.ONLINE -> OnlineBadge()
                    DeviceStatus.OFFLINE -> OfflineBadge()
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.lg))

        // Informations réseau
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = extendedColors.cardSurface,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = Spacing.xs),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.lg),
            ) {
                Text(
                    text = "Informations réseau",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(Spacing.md))
                HorizontalDivider(color = extendedColors.divider)
                Spacer(modifier = Modifier.height(Spacing.md))

                DeviceInfoRow(
                    label = "IP WireGuard",
                    value = device.wgIp,
                    contentDescriptionText = "Adresse IP WireGuard : ${device.wgIp}",
                )

                Spacer(modifier = Modifier.height(Spacing.md))

                DeviceInfoRow(
                    label = "Dernier heartbeat",
                    value = formatHeartbeat(device.lastHeartbeat),
                    contentDescriptionText = "Dernier heartbeat : ${formatHeartbeat(device.lastHeartbeat)}",
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.sm))

        CacheTimestamp(
            syncedAt = syncedAt,
            modifier = Modifier.align(Alignment.End),
        )
    }
}

// ── Ligne info ─────────────────────────────────────────────────────────────────

@Composable
private fun DeviceInfoRow(
    label: String,
    value: String,
    contentDescriptionText: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = contentDescriptionText },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
