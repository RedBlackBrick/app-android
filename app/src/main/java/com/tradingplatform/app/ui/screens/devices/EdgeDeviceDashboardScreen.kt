package com.tradingplatform.app.ui.screens.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradingplatform.app.data.local.db.CacheTtl
import com.tradingplatform.app.domain.model.BrokerConnection
import com.tradingplatform.app.domain.model.BrokerGatewayStatus
import com.tradingplatform.app.domain.model.Device
import com.tradingplatform.app.domain.model.DeviceStatus
import com.tradingplatform.app.ui.components.CPU_THRESHOLDS
import com.tradingplatform.app.ui.components.CacheTimestamp
import com.tradingplatform.app.ui.components.DISK_THRESHOLDS
import com.tradingplatform.app.ui.components.EmptyDevicesIllustration
import com.tradingplatform.app.ui.components.EmptyState
import com.tradingplatform.app.ui.components.LoadingOverlay
import com.tradingplatform.app.ui.components.MEMORY_THRESHOLDS
import com.tradingplatform.app.ui.components.MetricRow
import com.tradingplatform.app.ui.components.OfflineBadge
import com.tradingplatform.app.ui.components.OnlineBadge
import com.tradingplatform.app.ui.components.StatusBadge
import com.tradingplatform.app.ui.components.TEMPERATURE_THRESHOLDS
import com.tradingplatform.app.ui.components.TradingCard
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing
import com.tradingplatform.app.ui.theme.asNumeric

/**
 * Écran d'état d'un device Radxa (admin uniquement) — LECTURE SEULE.
 *
 * Route `device/{deviceId}`. Toute la gestion (firmware, désappairage, redémarrage…) se fait
 * sur la plateforme web ; l'app ne montre que l'état :
 * - résumé : nom, statut en ligne / hors ligne, dernière vue, IP WireGuard, firmware, uptime ;
 * - ressources : CPU, mémoire, température, disque (seuils de `MetricsComponents`) ;
 * - scraping (si le device remonte des métriques) ;
 * - broker gateway + connexions broker ;
 * - horodatage du cache.
 *
 * @param deviceId identifiant du device (navigation args)
 * @param onNavigateBack retour à la liste
 * @param viewModel injecté par Hilt
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EdgeDeviceDashboardScreen(
    deviceId: String,
    onNavigateBack: () -> Unit,
    viewModel: DeviceDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val brokerState by viewModel.brokerState.collectAsStateWithLifecycle()

    // Charger le device et ses connexions broker dès l'affichage (ou si deviceId change)
    LaunchedEffect(deviceId) {
        viewModel.loadDevice(deviceId)
        viewModel.loadBrokerConnections(deviceId)
    }

    val reload: () -> Unit = {
        viewModel.refresh(deviceId)
        viewModel.loadBrokerConnections(deviceId)
    }

    val screenTitle = (uiState as? DeviceDetailUiState.Success)?.device?.let { it.name ?: it.id }
        ?: "Device"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = screenTitle) },
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
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = reload,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val state = uiState) {
                is DeviceDetailUiState.Loading -> LoadingOverlay()

                is DeviceDetailUiState.Success -> DashboardContent(
                    device = state.device,
                    syncedAt = state.syncedAt,
                    brokerState = brokerState,
                )

                is DeviceDetailUiState.Error -> Box(
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
                        onAction = reload,
                    )
                }
            }
        }
    }
}

// ── Contenu principal ─────────────────────────────────────────────────────────

@Composable
private fun DashboardContent(
    device: Device,
    syncedAt: Long,
    brokerState: BrokerUiState,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        item { DeviceSummaryCard(device = device) }
        item { ResourcesCard(device = device) }
        if (device.lastTicksSent != null || device.lastScraperErrors != null) {
            item { ScrapingCard(device = device) }
        }
        item { BrokerGatewayCard(brokerGateway = device.brokerGateway, brokerState = brokerState) }
        item { DashboardFooter(syncedAt = syncedAt) }
    }
}

// ── Cartes ────────────────────────────────────────────────────────────────────

/** Nom + statut en ligne / hors ligne, puis dernière vue, IP WireGuard, firmware, uptime. */
@Composable
private fun DeviceSummaryCard(
    device: Device,
    modifier: Modifier = Modifier,
) {
    val displayName = device.name ?: device.id

    TradingCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                StatusLed(isOnline = device.status == DeviceStatus.ONLINE)
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "Nom du device : $displayName" },
                )
                when (device.status) {
                    DeviceStatus.ONLINE -> OnlineBadge()
                    DeviceStatus.OFFLINE -> OfflineBadge()
                }
            }

            HorizontalDivider(color = LocalExtendedColors.current.divider)

            InfoRow(
                label = "Dernière vue",
                value = device.lastHeartbeat?.let { formatHeartbeat(it) },
            )
            InfoRow(label = "IP WireGuard", value = device.wgIp)
            InfoRow(label = "Firmware", value = device.firmwareVersion)
            InfoRow(
                label = "Uptime",
                value = device.uptimeSeconds?.let { formatUptime(it) },
            )
        }
    }
}

/** CPU, mémoire, température, disque — barres colorées selon les seuils de `MetricsComponents`. */
@Composable
private fun ResourcesCard(
    device: Device,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "Ressources", modifier = modifier) {
        MetricRow(
            label = "CPU",
            value = device.cpuPct,
            unit = "%",
            thresholds = CPU_THRESHOLDS,
            progressMax = 100f,
        )
        MetricRow(
            label = "Mémoire",
            value = device.memoryPct,
            unit = "%",
            thresholds = MEMORY_THRESHOLDS,
            progressMax = 100f,
        )
        MetricRow(
            label = "Température",
            value = device.temperature,
            unit = "°C",
            thresholds = TEMPERATURE_THRESHOLDS,
            progressMax = 100f,
        )
        MetricRow(
            label = "Disque",
            value = device.diskPct,
            unit = "%",
            thresholds = DISK_THRESHOLDS,
            progressMax = 100f,
        )
    }
}

/** Métriques de scraping remontées par le device (affichée seulement si elles existent). */
@Composable
private fun ScrapingCard(
    device: Device,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "Scraping", modifier = modifier) {
        device.lastTicksSent?.let { ticks ->
            InfoRow(label = "Ticks envoyés", value = "%,d".format(ticks))
        }
        device.lastScraperErrors?.let { errors ->
            InfoRow(
                label = "Erreurs scraping",
                value = errors.toString(),
                valueColor = if (errors > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

/** Statut de la broker gateway (vocabulaire backend) + connexions broker du device. */
@Composable
private fun BrokerGatewayCard(
    brokerGateway: BrokerGatewayStatus?,
    brokerState: BrokerUiState,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "Broker Gateway", modifier = modifier) {
        StatusRow(
            label = "Statut",
            spokenLabel = "Statut de la broker gateway",
            display = brokerGatewayDisplay(brokerGateway),
        )
        BrokerConnectionsSection(brokerState = brokerState)
    }
}

@Composable
private fun BrokerConnectionsSection(
    brokerState: BrokerUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        when (brokerState) {
            is BrokerUiState.Idle -> Unit

            is BrokerUiState.Loading -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.semantics {
                        contentDescription = "Chargement des connexions broker"
                    },
                )
            }

            is BrokerUiState.Error -> Text(
                text = brokerState.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            is BrokerUiState.Success -> {
                if (brokerState.connections.isEmpty()) {
                    Text(
                        text = "Aucune connexion broker",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    brokerState.connections.forEach { connection ->
                        BrokerConnectionRow(connection = connection)
                    }
                }
            }
        }
    }
}

@Composable
private fun BrokerConnectionRow(
    connection: BrokerConnection,
    modifier: Modifier = Modifier,
) {
    val brokerName = brokerDisplayName(connection.brokerCode)
    StatusRow(
        label = brokerName,
        spokenLabel = "Broker $brokerName, statut",
        display = brokerConnectionDisplay(connection.connectionStatus),
        modifier = modifier,
    )
}

/** Horodatage du cache + rappel discret : la gestion complète est sur la plateforme web. */
@Composable
private fun DashboardFooter(
    syncedAt: Long,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        CacheTimestamp(
            syncedAt = syncedAt,
            ttlMs = CacheTtl.DEVICES_MS,
        )
        Text(
            text = "Gestion complète (firmware, désappairage…) sur la plateforme web",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ── Briques communes ──────────────────────────────────────────────────────────

/** Carte avec titre de section, séparateur et contenu espacé uniformément. */
@Composable
private fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    TradingCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(color = LocalExtendedColors.current.divider)
            content()
        }
    }
}

/** Ligne « libellé … valeur » ; valeur en mono (chiffres tabulaires), « — » si [value] est nul. */
@Composable
private fun InfoRow(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = "$label : ${value ?: "inconnu"}" },
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value ?: "—",
            style = MaterialTheme.typography.bodyMedium.asNumeric(),
            color = valueColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Ligne « libellé … [badge de statut] » ; lue par TalkBack comme « spokenLabel : statut ». */
@Composable
private fun StatusRow(
    label: String,
    spokenLabel: String,
    display: StatusDisplay,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = "$spokenLabel : ${display.label}" },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        StatusBadge(text = display.label, color = display.tone.color())
    }
}

@Composable
private fun StatusTone.color(): Color {
    val extended = LocalExtendedColors.current
    return when (this) {
        StatusTone.SUCCESS -> extended.statusOnline
        StatusTone.WARNING -> extended.statusWarning
        StatusTone.ERROR -> extended.statusOffline
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

// ── Formatters ────────────────────────────────────────────────────────────────

/**
 * Formate un uptime en secondes sous forme lisible : "2j 4h 30m" ou "45m 12s".
 */
internal fun formatUptime(seconds: Long): String {
    if (seconds < 0) return "—"
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60

    return when {
        days > 0 -> buildString {
            append("${days}j")
            if (hours > 0) append(" ${hours}h")
            if (minutes > 0) append(" ${minutes}m")
        }
        hours > 0 -> buildString {
            append("${hours}h")
            if (minutes > 0) append(" ${minutes}m")
        }
        minutes > 0 -> "${minutes}m ${secs}s"
        else -> "${secs}s"
    }
}
