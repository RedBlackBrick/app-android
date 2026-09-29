package com.tradingplatform.app.ui.screens.devices

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradingplatform.app.data.local.db.CacheTtl
import com.tradingplatform.app.domain.model.Device
import com.tradingplatform.app.domain.model.DeviceStatus
import com.tradingplatform.app.ui.components.CacheTimestamp
import com.tradingplatform.app.ui.components.CompactHealthBar
import com.tradingplatform.app.ui.components.EmptyDevicesIllustration
import com.tradingplatform.app.ui.components.EmptyState
import com.tradingplatform.app.ui.components.HealthStatusBadge
import com.tradingplatform.app.ui.components.OfflineBadge
import com.tradingplatform.app.ui.components.OnlineBadge
import com.tradingplatform.app.ui.components.SkeletonDeviceCard
import com.tradingplatform.app.ui.components.TradingCard
import com.tradingplatform.app.ui.theme.IconSize
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing
import com.tradingplatform.app.ui.theme.asNumeric
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Liste des devices (flotte admin) — état uniquement ; la gestion se fait sur la plateforme web.
 * Le bouton « + » lance le flux de pairing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    onNavigateToDetail: (deviceId: String) -> Unit,
    onNavigateToPairing: () -> Unit,
    onNavigateBack: () -> Unit = {},
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Flotte d'appareils") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Retour",
                        )
                    }
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
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
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
                ttlMs = CacheTtl.DEVICES_MS,
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

/**
 * Carte d'un device : nom, statut, santé, IP WireGuard, dernière vue. Lue par TalkBack comme un
 * seul élément cliquable (les badges portent leur propre « Statut : … »).
 */
@Composable
private fun DeviceCard(
    device: Device,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isOnline = device.status == DeviceStatus.ONLINE

    TradingCard(modifier = modifier, onClick = onClick) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
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
                    StatusLed(isOnline = isOnline)
                    Text(
                        text = device.name ?: device.id,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isOnline) {
                        HealthStatusBadge(
                            cpuPct = device.cpuPct,
                            memoryPct = device.memoryPct,
                            temperature = device.temperature,
                            diskPct = device.diskPct,
                        )
                    }
                    when (device.status) {
                        DeviceStatus.ONLINE -> OnlineBadge()
                        DeviceStatus.OFFLINE -> OfflineBadge()
                    }
                }
            }

            LabeledValue(label = "IP WireGuard", value = device.wgIp)
            LabeledValue(label = "Dernière vue", value = device.lastHeartbeat?.let { formatHeartbeat(it) })

            // Barres de santé — seulement pour un device en ligne avec des métriques
            if (isOnline) {
                CompactHealthBar(
                    cpuPct = device.cpuPct,
                    memoryPct = device.memoryPct,
                    temperature = device.temperature,
                )
            }
        }
    }
}

/** « libellé  valeur » : valeur en mono (chiffres tabulaires), « — » si [value] est nul. */
@Composable
private fun LabeledValue(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = "$label : ${value ?: "inconnue"}"
        },
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value ?: "—",
            style = MaterialTheme.typography.bodySmall.asNumeric(),
            color = MaterialTheme.colorScheme.onSurface,
        )
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
