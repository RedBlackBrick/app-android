package com.tradingplatform.app.ui.screens.devices

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradingplatform.app.domain.model.BrokerConnection
import com.tradingplatform.app.domain.model.BrokerGatewayStatus
import com.tradingplatform.app.domain.model.Device
import com.tradingplatform.app.domain.model.DeviceStatus
import com.tradingplatform.app.ui.components.CacheTimestamp
import com.tradingplatform.app.ui.components.CPU_THRESHOLDS
import com.tradingplatform.app.ui.components.DISK_THRESHOLDS
import com.tradingplatform.app.ui.components.ErrorBanner
import com.tradingplatform.app.ui.components.LoadingOverlay
import com.tradingplatform.app.ui.components.MEMORY_THRESHOLDS
import com.tradingplatform.app.ui.components.MetricRow
import com.tradingplatform.app.ui.components.OfflineBadge
import com.tradingplatform.app.ui.components.OnlineBadge
import com.tradingplatform.app.ui.components.TEMPERATURE_THRESHOLDS
import com.tradingplatform.app.ui.components.rememberHapticFeedback
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing

/**
 * Ecran dashboard riche pour un device Radxa (admin uniquement).
 *
 * Remplace [DeviceDetailScreen] sur la route `device/{deviceId}`.
 *
 * Affiche :
 * - Header avec LED pulsante + nom + badge online/offline
 * - Card "Connexion" : hostname, IP WireGuard, firmware, uptime
 * - Card "Ressources" : CPU, mémoire, température, disque avec barres de progression colorées
 * - Card "Actions" : Reboot, Health Check, Update Firmware (avec confirmations)
 * - Bouton "Dépannage local" si OFFLINE
 * - Bouton "Désappairer" avec confirmation BottomSheet
 *
 * @param deviceId identifiant du device (navigation args)
 * @param onNavigateBack retour à la liste
 * @param onNavigateToLocalMaintenance ouvre le dépannage LAN si OFFLINE
 * @param viewModel injecté par Hilt
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EdgeDeviceDashboardScreen(
    deviceId: String,
    onNavigateBack: () -> Unit,
    onNavigateToLocalMaintenance: () -> Unit = {},
    viewModel: DeviceDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val unpairState by viewModel.unpairState.collectAsStateWithLifecycle()
    val commandState by viewModel.commandState.collectAsStateWithLifecycle()
    val brokerState by viewModel.brokerState.collectAsStateWithLifecycle()
    val brokerTestState by viewModel.brokerTestState.collectAsStateWithLifecycle()
    val haptic = rememberHapticFeedback()

    val deviceName = (uiState as? DeviceDetailUiState.Success)?.device?.name ?: "ce device"

    // Charger le device et les connexions broker dès l'affichage (ou si deviceId change)
    LaunchedEffect(deviceId) {
        viewModel.loadDevice(deviceId)
        viewModel.loadBrokerConnections(deviceId)
    }

    // Navigation back après unpair réussi
    LaunchedEffect(unpairState) {
        if (unpairState is UnpairState.Success) {
            viewModel.resetUnpairState()
            onNavigateBack()
        }
    }

    // Reset commandState Success après affichage (évite le re-trigger sur recomposition)
    LaunchedEffect(commandState) {
        if (commandState is CommandState.Success) {
            kotlinx.coroutines.delay(2_000)
            viewModel.resetCommandState()
        }
    }

    // ── BottomSheet confirmation désappairage ─────────────────────────────────

    if (unpairState is UnpairState.Confirming) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.cancelUnpair() },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.xxl),
            ) {
                Text(
                    text = "Désappairer le device ?",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(Spacing.md))
                Text(
                    text = "Cette action va révoquer l'accès VPN de « $deviceName », " +
                        "arrêter les services et supprimer la configuration. " +
                        "Cette action est irréversible.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(Spacing.xl))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    OutlinedButton(
                        onClick = { viewModel.cancelUnpair() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Annuler")
                    }
                    Button(
                        onClick = {
                            haptic.reject()
                            viewModel.confirmUnpair(deviceId)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("Désappairer")
                    }
                }
            }
        }
    }

    // ── Dialog confirmation commandes destructives ────────────────────────────

    val confirmingCommand = (commandState as? CommandState.Confirming)?.commandType
    if (confirmingCommand != null && confirmingCommand != CommandType.HEALTH_CHECK) {
        val (title, body) = when (confirmingCommand) {
            CommandType.REBOOT ->
                "Redémarrer le device ?" to
                    "Le device « $deviceName » sera redémarré. Il sera temporairement hors ligne."
            CommandType.UPDATE_FIRMWARE ->
                "Mettre à jour le firmware ?" to
                    "La mise à jour du firmware de « $deviceName » sera lancée. " +
                    "Le device redémarrera automatiquement à la fin."
            else -> "" to ""
        }
        AlertDialog(
            onDismissRequest = { viewModel.cancelCommand() },
            title = { Text(title) },
            text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                Button(
                    onClick = {
                        haptic.reject()
                        viewModel.sendCommand(deviceId, confirmingCommand)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Confirmer")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelCommand() }) {
                    Text("Annuler")
                }
            },
        )
    }

    // Health Check → envoi direct sans dialog
    LaunchedEffect(commandState) {
        val confirming = commandState as? CommandState.Confirming ?: return@LaunchedEffect
        if (confirming.commandType == CommandType.HEALTH_CHECK) {
            viewModel.sendCommand(deviceId, CommandType.HEALTH_CHECK)
        }
    }

    // ── Dialog in progress / erreur commande ─────────────────────────────────

    if (commandState is CommandState.InProgress) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Commande en cours...") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                }
            },
            confirmButton = {},
        )
    }

    if (commandState is CommandState.Error) {
        AlertDialog(
            onDismissRequest = { viewModel.resetCommandState() },
            title = { Text("Erreur commande") },
            text = { Text((commandState as CommandState.Error).message) },
            confirmButton = {
                TextButton(onClick = { viewModel.resetCommandState() }) {
                    Text("OK")
                }
            },
        )
    }

    if (commandState is CommandState.Success) {
        val label = when ((commandState as CommandState.Success).commandType) {
            CommandType.REBOOT -> "Redémarrage lancé"
            CommandType.HEALTH_CHECK -> "Health check envoyé"
            CommandType.UPDATE_FIRMWARE -> "Mise à jour lancée"
        }
        AlertDialog(
            onDismissRequest = { viewModel.resetCommandState() },
            title = { Text(label) },
            text = {
                Text(
                    "La commande a été transmise avec succès au device.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.resetCommandState() }) {
                    Text("OK")
                }
            },
        )
    }

    // ── Dialog in progress unpair ─────────────────────────────────────────────

    if (unpairState is UnpairState.InProgress) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Désappairage en cours...") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                }
            },
            confirmButton = {},
        )
    }

    if (unpairState is UnpairState.Error) {
        AlertDialog(
            onDismissRequest = { viewModel.resetUnpairState() },
            title = { Text("Erreur") },
            text = { Text((unpairState as UnpairState.Error).message) },
            confirmButton = {
                TextButton(onClick = { viewModel.resetUnpairState() }) {
                    Text("OK")
                }
            },
        )
    }

    // ── Scaffold principal ────────────────────────────────────────────────────

    val screenTitle = (uiState as? DeviceDetailUiState.Success)?.device?.name ?: "Dashboard"
    val isRefreshing = uiState is DeviceDetailUiState.Loading
    val pullRefreshState = rememberPullToRefreshState()

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
            onRefresh = {
                viewModel.refresh(deviceId)
                viewModel.loadBrokerConnections(deviceId)
            },
            state = pullRefreshState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                when (val state = uiState) {
                    is DeviceDetailUiState.Loading -> {
                        // LoadingOverlay géré ci-dessous
                    }

                    is DeviceDetailUiState.Success -> {
                        DashboardContent(
                            device = state.device,
                            syncedAt = state.syncedAt,
                            brokerState = brokerState,
                            brokerTestState = brokerTestState,
                            onNavigateToLocalMaintenance = onNavigateToLocalMaintenance,
                            onUnpair = { viewModel.requestUnpair() },
                            onSendCommand = { commandType ->
                                viewModel.requestCommand(commandType)
                            },
                            onTestBroker = { viewModel.testBrokerConnection(deviceId) },
                            onRemoveBroker = { portfolioId ->
                                viewModel.removeBrokerConnection(deviceId, portfolioId)
                            },
                            onResetBrokerTest = { viewModel.resetBrokerTestState() },
                        )
                    }

                    is DeviceDetailUiState.Error -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(Spacing.lg),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = "Impossible de charger les informations du device",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (uiState is DeviceDetailUiState.Loading) {
                    LoadingOverlay()
                }

                if (uiState is DeviceDetailUiState.Error) {
                    ErrorBanner(
                        message = (uiState as DeviceDetailUiState.Error).message,
                        onRetry = { viewModel.refresh(deviceId) },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}

// ── Contenu principal du dashboard ────────────────────────────────────────────

@Composable
private fun DashboardContent(
    device: Device,
    syncedAt: Long,
    brokerState: BrokerUiState,
    brokerTestState: BrokerTestState,
    onNavigateToLocalMaintenance: () -> Unit,
    onUnpair: () -> Unit,
    onSendCommand: (CommandType) -> Unit,
    onTestBroker: () -> Unit,
    onRemoveBroker: (String) -> Unit,
    onResetBrokerTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        // ── Header : LED + nom + badge statut ─────────────────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = extendedColors.cardSurface),
                elevation = CardDefaults.cardElevation(defaultElevation = Spacing.xs),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.lg),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        StatusLed(isOnline = device.status == DeviceStatus.ONLINE)
                        Text(
                            text = device.name ?: device.id,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .weight(1f)
                                .semantics {
                                    contentDescription = "Nom du device : ${device.name ?: device.id}"
                                },
                        )
                        when (device.status) {
                            DeviceStatus.ONLINE -> OnlineBadge()
                            DeviceStatus.OFFLINE -> OfflineBadge()
                        }
                    }
                }
            }
        }

        // ── Card "Connexion" ──────────────────────────────────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = extendedColors.cardSurface),
                elevation = CardDefaults.cardElevation(defaultElevation = Spacing.xs),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.lg),
                ) {
                    Text(
                        text = "Connexion",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(Spacing.md))
                    HorizontalDivider(color = extendedColors.divider)
                    Spacer(modifier = Modifier.height(Spacing.md))

                    DashboardInfoRow(
                        label = "Hostname",
                        value = device.hostname ?: "—",
                        contentDescriptionText = "Hostname : ${device.hostname ?: "inconnu"}",
                    )
                    Spacer(modifier = Modifier.height(Spacing.md))
                    DashboardInfoRow(
                        label = "IP WireGuard",
                        value = device.wgIp ?: "—",
                        contentDescriptionText = "Adresse IP WireGuard : ${device.wgIp ?: "—"}",
                    )
                    Spacer(modifier = Modifier.height(Spacing.md))
                    DashboardInfoRow(
                        label = "Firmware",
                        value = device.firmwareVersion ?: "—",
                        contentDescriptionText = "Version firmware : ${device.firmwareVersion ?: "inconnue"}",
                    )
                    Spacer(modifier = Modifier.height(Spacing.md))
                    DashboardInfoRow(
                        label = "Uptime",
                        value = device.uptimeSeconds?.let { formatUptime(it) } ?: "—",
                        contentDescriptionText = "Uptime : ${device.uptimeSeconds?.let { formatUptime(it) } ?: "inconnu"}",
                    )
                    Spacer(modifier = Modifier.height(Spacing.md))
                    DashboardInfoRow(
                        label = "Dernier heartbeat",
                        value = formatHeartbeat(device.lastHeartbeat),
                        contentDescriptionText = "Dernier heartbeat : ${formatHeartbeat(device.lastHeartbeat)}",
                    )
                }
            }
        }

        // ── Card "Ressources" ─────────────────────────────────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = extendedColors.cardSurface),
                elevation = CardDefaults.cardElevation(defaultElevation = Spacing.xs),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.lg),
                ) {
                    Text(
                        text = "Ressources",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(Spacing.md))
                    HorizontalDivider(color = extendedColors.divider)
                    Spacer(modifier = Modifier.height(Spacing.md))

                    MetricRow(
                        label = "CPU",
                        value = device.cpuPct,
                        unit = "%",
                        thresholds = CPU_THRESHOLDS,
                        progressMax = 100f,
                    )
                    Spacer(modifier = Modifier.height(Spacing.md))
                    MetricRow(
                        label = "Mémoire",
                        value = device.memoryPct,
                        unit = "%",
                        thresholds = MEMORY_THRESHOLDS,
                        progressMax = 100f,
                    )
                    Spacer(modifier = Modifier.height(Spacing.md))
                    MetricRow(
                        label = "Température",
                        value = device.temperature,
                        unit = "°C",
                        thresholds = TEMPERATURE_THRESHOLDS,
                        progressMax = 100f,
                    )
                    Spacer(modifier = Modifier.height(Spacing.md))
                    MetricRow(
                        label = "Disque",
                        value = device.diskPct,
                        unit = "%",
                        thresholds = DISK_THRESHOLDS,
                        progressMax = 100f,
                    )
                }
            }
        }

        // ── Card "Scraping" (if scraping metrics available) ───────────────────
        if (device.lastTicksSent != null || device.scrapersCircuit != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = extendedColors.cardSurface),
                    elevation = CardDefaults.cardElevation(defaultElevation = Spacing.xs),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.lg),
                    ) {
                        Text(
                            text = "Scraping",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(Spacing.md))
                        HorizontalDivider(color = extendedColors.divider)
                        Spacer(modifier = Modifier.height(Spacing.md))

                        // Ticks sent + errors
                        if (device.lastTicksSent != null) {
                            DashboardInfoRow(
                                label = "Ticks envoy\u00e9s",
                                value = "%,d".format(device.lastTicksSent),
                                contentDescriptionText = "Ticks envoy\u00e9s : ${device.lastTicksSent}",
                            )
                            Spacer(modifier = Modifier.height(Spacing.md))
                        }
                        if (device.lastScraperErrors != null) {
                            DashboardInfoRow(
                                label = "Erreurs scraping",
                                value = "${device.lastScraperErrors}",
                                contentDescriptionText = "Erreurs scraping : ${device.lastScraperErrors}",
                            )
                            Spacer(modifier = Modifier.height(Spacing.md))
                        }

                        // Circuit breaker states per scraper
                        device.scrapersCircuit?.forEach { (scraperName, circuit) ->
                            val stateColor = when (circuit.state.lowercase()) {
                                "closed" -> LocalExtendedColors.current.pnlPositive
                                "half_open" -> LocalExtendedColors.current.warning
                                else -> MaterialTheme.colorScheme.error
                            }
                            val stateLabel = when (circuit.state.lowercase()) {
                                "closed" -> "OK"
                                "half_open" -> "Test"
                                "open" -> "Arr\u00eat\u00e9"
                                else -> circuit.state
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = scraperName.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (circuit.consecutiveFailures > 0) {
                                        Text(
                                            text = "${circuit.consecutiveFailures} err",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                    Text(
                                        text = stateLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = stateColor,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(Spacing.sm))
                        }
                    }
                }
            }
        }

        // ── Card "Broker Gateway" ────────────────────────────────────────────
        item {
            BrokerGatewayCard(
                brokerGateway = device.brokerGateway,
                brokerState = brokerState,
                brokerTestState = brokerTestState,
                onTestBroker = onTestBroker,
                onRemoveBroker = onRemoveBroker,
                onResetBrokerTest = onResetBrokerTest,
            )
        }

        // ── Card "Actions" ────────────────────────────────────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = extendedColors.cardSurface),
                elevation = CardDefaults.cardElevation(defaultElevation = Spacing.xs),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.lg),
                ) {
                    Text(
                        text = "Actions",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(Spacing.md))
                    HorizontalDivider(color = extendedColors.divider)
                    Spacer(modifier = Modifier.height(Spacing.md))

                    OutlinedButton(
                        onClick = { onSendCommand(CommandType.HEALTH_CHECK) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "Lancer un health check sur ${device.name}"
                            },
                    ) {
                        Text("Health Check")
                    }
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    OutlinedButton(
                        onClick = { onSendCommand(CommandType.REBOOT) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "Redémarrer ${device.name}"
                            },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("Redémarrer")
                    }
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    OutlinedButton(
                        onClick = { onSendCommand(CommandType.UPDATE_FIRMWARE) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "Mettre à jour le firmware de ${device.name}"
                            },
                    ) {
                        Text("Mettre à jour le firmware")
                    }
                }
            }
        }

        // ── Timestamp cache ───────────────────────────────────────────────────
        item {
            CacheTimestamp(
                syncedAt = syncedAt,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ── Bouton "Dépannage local" (OFFLINE uniquement) ─────────────────────
        if (device.status == DeviceStatus.OFFLINE) {
            item {
                OutlinedButton(
                    onClick = onNavigateToLocalMaintenance,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "Ouvrir le dépannage local pour ${device.name}"
                        },
                ) {
                    Text("Dépannage local")
                }
            }
        }

        // ── Bouton "Désappairer" ──────────────────────────────────────────────
        item {
            OutlinedButton(
                onClick = onUnpair,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Désappairer ${device.name}"
                    },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Désappairer le device")
            }
        }

        // espace final pour ne pas coller au bord
        item { Spacer(modifier = Modifier.height(Spacing.lg)) }
    }
}

// ── Card "Broker Gateway" ─────────────────────────────────────────────────────

@Composable
private fun BrokerGatewayCard(
    brokerGateway: BrokerGatewayStatus?,
    brokerState: BrokerUiState,
    brokerTestState: BrokerTestState,
    onTestBroker: () -> Unit,
    onRemoveBroker: (String) -> Unit,
    onResetBrokerTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = extendedColors.cardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = Spacing.xs),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
        ) {
            Text(
                text = "Broker Gateway",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(Spacing.md))
            HorizontalDivider(color = extendedColors.divider)
            Spacer(modifier = Modifier.height(Spacing.md))

            // Gateway status summary
            if (brokerGateway == null || !brokerGateway.enabled) {
                Text(
                    text = "Non configur\u00e9",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics {
                        contentDescription = "Broker gateway : non configur\u00e9"
                    },
                )
            } else {
                val statusColor = when (brokerGateway.status.lowercase()) {
                    "connected", "active" -> extendedColors.pnlPositive
                    "disconnected", "inactive" -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Statut",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = brokerGateway.status.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                        modifier = Modifier.semantics {
                            contentDescription = "Statut broker gateway : ${brokerGateway.status}"
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            // Broker connections list
            when (brokerState) {
                is BrokerUiState.Idle -> {
                    // Nothing to display yet
                }

                is BrokerUiState.Loading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.semantics {
                                contentDescription = "Chargement des connexions broker"
                            },
                        )
                    }
                }

                is BrokerUiState.Error -> {
                    Text(
                        text = brokerState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                is BrokerUiState.Success -> {
                    if (brokerState.connections.isEmpty()) {
                        Text(
                            text = "Aucune connexion broker",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        brokerState.connections.forEach { connection ->
                            BrokerConnectionRow(
                                connection = connection,
                                onRemove = {
                                    connection.portfolioId?.let { onRemoveBroker(it) }
                                },
                            )
                            Spacer(modifier = Modifier.height(Spacing.sm))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            // Test connection button
            if (brokerGateway != null && brokerGateway.enabled) {
                OutlinedButton(
                    onClick = onTestBroker,
                    enabled = brokerTestState !is BrokerTestState.Testing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "Tester la connexion broker"
                        },
                ) {
                    if (brokerTestState is BrokerTestState.Testing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .height(Spacing.lg)
                                .padding(end = Spacing.sm),
                            strokeWidth = Spacing.xs,
                        )
                    }
                    Text("Tester la connexion")
                }

                // Test result display
                when (brokerTestState) {
                    is BrokerTestState.Result -> {
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        val resultColor = if (brokerTestState.healthy) {
                            extendedColors.pnlPositive
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                        val resultText = if (brokerTestState.healthy) {
                            "Connexion OK"
                        } else {
                            brokerTestState.message ?: "Connexion \u00e9chou\u00e9e"
                        }
                        Text(
                            text = resultText,
                            style = MaterialTheme.typography.bodySmall,
                            color = resultColor,
                            modifier = Modifier.semantics {
                                contentDescription = "R\u00e9sultat du test : $resultText"
                            },
                        )
                    }

                    is BrokerTestState.Error -> {
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        Text(
                            text = brokerTestState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    else -> {
                        // Idle or Testing — no result to display
                    }
                }
            }
        }
    }
}

// ── Ligne connexion broker ────────────────────────────────────────────────────

@Composable
private fun BrokerConnectionRow(
    connection: BrokerConnection,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current
    val statusColor = when (connection.connectionStatus?.lowercase()) {
        "connected", "active" -> extendedColors.pnlPositive
        "disconnected", "inactive", "error" -> MaterialTheme.colorScheme.error
        "deploying", "pending" -> extendedColors.warning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Broker ${connection.brokerCode}, " +
                    "statut : ${connection.connectionStatus ?: "inconnu"}"
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = connection.brokerCode.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = connection.connectionStatus?.replaceFirstChar { it.uppercase() } ?: "—",
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                )
                if (connection.executionMode != null) {
                    Text(
                        text = connection.executionMode.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (connection.portfolioId != null) {
            TextButton(
                onClick = onRemove,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(
                    text = "Retirer",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

// ── Ligne info texte ───────────────────────────────────────────────────────────

@Composable
private fun DashboardInfoRow(
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
