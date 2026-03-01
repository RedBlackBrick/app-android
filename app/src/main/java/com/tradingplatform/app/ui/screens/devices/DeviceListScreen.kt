package com.tradingplatform.app.ui.screens.devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Ecran de liste des devices (admin uniquement).
 *
 * La condition d'accès admin est vérifiée au niveau du NavGraph (Phase 8).
 * Affiche la liste des devices avec leur statut, IP WireGuard et horodatage du dernier
 * heartbeat. Pull-to-refresh supporté. FAB pour déclencher le flux de pairing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    onNavigateToDetail: (deviceId: String) -> Unit,
    onNavigateToPairing: () -> Unit,
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val isRefreshing = uiState is DevicesUiState.Loading
    val pullRefreshState = rememberPullToRefreshState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = "Devices")
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToPairing,
                modifier = Modifier.semantics {
                    contentDescription = "Ajouter un device"
                },
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { viewModel.refresh() },
                state = pullRefreshState,
                modifier = Modifier.fillMaxSize(),
            ) {
                when (val state = uiState) {
                    is DevicesUiState.Loading -> {
                        // Contenu vide pendant le chargement — LoadingOverlay affiché par-dessus
                    }

                    is DevicesUiState.Success -> {
                        DeviceListContent(
                            devices = state.devices,
                            syncedAt = state.syncedAt,
                            onNavigateToDetail = onNavigateToDetail,
                        )
                    }

                    is DevicesUiState.Error -> {
                        // Affiche un message d'erreur centré quand la liste est vide
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(Spacing.lg),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = "Impossible de charger les devices",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // LoadingOverlay positionné par-dessus tout le contenu
            if (uiState is DevicesUiState.Loading) {
                LoadingOverlay()
            }

            // ErrorBanner ancré en bas
            if (uiState is DevicesUiState.Error) {
                val errorMessage = (uiState as DevicesUiState.Error).message
                ErrorBanner(
                    message = errorMessage,
                    onRetry = { viewModel.refresh() },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

// ── Contenu liste ──────────────────────────────────────────────────────────────

@Composable
private fun DeviceListContent(
    devices: List<Device>,
    syncedAt: Long,
    onNavigateToDetail: (deviceId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        item {
            CacheTimestamp(
                syncedAt = syncedAt,
                modifier = Modifier.padding(bottom = Spacing.xs),
            )
        }

        if (devices.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.xxxl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Aucun device enregistré",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Text(
                        text = "Utilisez le bouton + pour ajouter un device via le flux de pairing.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(
                items = devices,
                key = { it.id },
            ) { device ->
                DeviceCard(
                    device = device,
                    onClick = { onNavigateToDetail(device.id) },
                )
            }
        }
    }
}

// ── Card device ────────────────────────────────────────────────────────────────

@Composable
private fun DeviceCard(
    device: Device,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current
    val cardBackground = extendedColors.cardSurface

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "Device ${device.name}, statut ${
                    if (device.status == DeviceStatus.ONLINE) "en ligne" else "hors ligne"
                }"
            },
        colors = CardDefaults.cardColors(
            containerColor = cardBackground,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = Spacing.xs),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )

                when (device.status) {
                    DeviceStatus.ONLINE -> OnlineBadge()
                    DeviceStatus.OFFLINE -> OfflineBadge()
                }
            }

            Spacer(modifier = Modifier.height(Spacing.sm))

            Text(
                text = "IP WireGuard : ${device.wgIp}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics {
                    contentDescription = "Adresse WireGuard : ${device.wgIp}"
                },
            )

            Spacer(modifier = Modifier.height(Spacing.xs))

            val heartbeatFormatted = formatHeartbeat(device.lastHeartbeat)
            Text(
                text = "Dernier heartbeat : $heartbeatFormatted",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics {
                    contentDescription = "Dernier heartbeat : $heartbeatFormatted"
                },
            )
        }
    }
}

// ── Formatage heartbeat ────────────────────────────────────────────────────────

private val HEARTBEAT_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")

internal fun formatHeartbeat(instant: java.time.Instant): String {
    val local = instant.atZone(ZoneId.systemDefault())
    return HEARTBEAT_FORMATTER.format(local)
}
