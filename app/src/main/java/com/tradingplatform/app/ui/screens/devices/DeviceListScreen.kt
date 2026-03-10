package com.tradingplatform.app.ui.screens.devices

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradingplatform.app.domain.model.Device
import com.tradingplatform.app.domain.model.DeviceStatus
import com.tradingplatform.app.ui.components.CacheTimestamp
import com.tradingplatform.app.ui.components.EmptyDevicesIllustration
import com.tradingplatform.app.ui.components.EmptyState
import com.tradingplatform.app.ui.components.OfflineBadge
import com.tradingplatform.app.ui.components.OnlineBadge
import com.tradingplatform.app.ui.components.SkeletonDeviceCard
import com.tradingplatform.app.ui.theme.IconSize
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

    val snackbarHostState = remember { SnackbarHostState() }
    val errorMessage = (uiState as? DevicesUiState.Error)?.message

    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            val result = snackbarHostState.showSnackbar(
                message = errorMessage,
                actionLabel = "Réessayer",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.refresh()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Devices") },
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
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing && uiState !is DevicesUiState.Loading,
            onRefresh = { viewModel.refresh() },
            state = pullRefreshState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val state = uiState) {
                is DevicesUiState.Loading -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        items(4) {
                            SkeletonDeviceCard()
                        }
                    }
                }

                is DevicesUiState.Success -> {
                    DeviceListContent(
                        devices = state.devices,
                        syncedAt = state.syncedAt,
                        onNavigateToDetail = onNavigateToDetail,
                        onNavigateToPairing = onNavigateToPairing,
                    )
                }

                is DevicesUiState.Error -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(Spacing.lg),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyState(
                            illustration = { EmptyDevicesIllustration() },
                            title = "Impossible de charger",
                            message = state.message,
                            actionLabel = "Réessayer",
                            onAction = { viewModel.refresh() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceListContent(
    devices: List<Device>,
    syncedAt: Long,
    onNavigateToDetail: (deviceId: String) -> Unit,
    onNavigateToPairing: () -> Unit,
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.xxxl),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        illustration = { EmptyDevicesIllustration() },
                        title = "Aucun device enregistré",
                        message = "Utilisez le bouton + pour ajouter un device via le flux de pairing.",
                        actionLabel = "Ajouter un device",
                        onAction = onNavigateToPairing,
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier.weight(1f),
                ) {
                    StatusLed(isOnline = device.status == DeviceStatus.ONLINE)
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

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

/**
 * Small LED dot that pulses softly when online, static gray when offline.
 */
@Composable
internal fun StatusLed(
    isOnline: Boolean,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current
    val color = if (isOnline) extendedColors.statusOnline else extendedColors.statusOffline

    if (isOnline) {
        val transition = rememberInfiniteTransition(label = "led_pulse")
        val alpha by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "led_alpha",
        )
        Box(
            modifier = modifier
                .size(IconSize.xs)
                .alpha(alpha)
                .clip(CircleShape)
                .background(color),
        )
    } else {
        Box(
            modifier = modifier
                .size(IconSize.xs)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.4f)),
        )
    }
}

private val HEARTBEAT_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")

internal fun formatHeartbeat(instant: java.time.Instant?): String {
    if (instant == null) return "—"
    val local = instant.atZone(ZoneId.systemDefault())
    return HEARTBEAT_FORMATTER.format(local)
}
