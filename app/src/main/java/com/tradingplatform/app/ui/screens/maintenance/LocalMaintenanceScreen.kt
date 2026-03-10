package com.tradingplatform.app.ui.screens.maintenance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tradingplatform.app.domain.model.DeviceLocalStatus
import com.tradingplatform.app.ui.components.rememberHapticFeedback
import com.tradingplatform.app.ui.theme.Elevation
import com.tradingplatform.app.ui.theme.LocalExtendedColors
import com.tradingplatform.app.ui.theme.Spacing

/**
 * Ecran de maintenance locale — permet d'envoyer des commandes à la Radxa via LAN
 * quand le tunnel WireGuard VPS est inopérant (roue de secours).
 *
 * 4 sections : Statut, WiFi, WireGuard, Système.
 *
 * Accessible depuis [DeviceDetailScreen] (admin uniquement) quand le device est offline.
 *
 * @param onNavigateBack callback pour revenir à l'écran précédent
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalMaintenanceScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LocalMaintenanceViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showRebootDialog by remember { mutableStateOf(false) }
    var wifiSsid by remember { mutableStateOf("") }
    var wifiPassword by remember { mutableStateOf("") }
    val haptic = rememberHapticFeedback()
    val snackbarHostState = remember { SnackbarHostState() }

    // Show snackbar when command result is received
    LaunchedEffect(uiState) {
        if (uiState is MaintenanceUiState.CommandResult) {
            snackbarHostState.showSnackbar(
                message = (uiState as MaintenanceUiState.CommandResult).message,
                duration = SnackbarDuration.Short,
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dépannage local") },
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
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = Spacing.lg)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Spacer(modifier = Modifier.height(Spacing.sm))

            when (val state = uiState) {
                is MaintenanceUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is MaintenanceUiState.Error -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Text(
                            text = state.message,
                            modifier = Modifier.padding(Spacing.lg),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Button(
                        onClick = viewModel::refreshStatus,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Réessayer")
                    }
                }

                is MaintenanceUiState.Ready -> {
                    StatusSection(status = state.status)

                    WifiSection(
                        ssid = wifiSsid,
                        onSsidChange = { wifiSsid = it },
                        password = wifiPassword,
                        onPasswordChange = { wifiPassword = it },
                        onConfigure = {
                            haptic.confirm()
                            viewModel.sendCommand(
                                action = "wifi_configure",
                                params = mapOf("ssid" to wifiSsid, "password" to wifiPassword),
                            )
                        },
                    )

                    WireGuardSection(
                        onRestart = {
                            haptic.confirm()
                            viewModel.sendCommand(action = "wireguard_restart")
                        },
                    )

                    SystemSection(
                        onLogs = { viewModel.sendCommand(action = "logs") },
                        onReboot = {
                            haptic.longPress()
                            showRebootDialog = true
                        },
                    )
                }

                is MaintenanceUiState.CommandResult -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Text(
                            text = state.message,
                            modifier = Modifier.padding(Spacing.lg),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = viewModel::refreshStatus,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Retour au statut")
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.lg))
        }
    }

    if (showRebootDialog) {
        AlertDialog(
            onDismissRequest = { showRebootDialog = false },
            title = { Text("Redémarrer le device ?") },
            text = { Text("Le device sera inaccessible pendant quelques minutes.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRebootDialog = false
                        viewModel.sendCommand(action = "reboot")
                    },
                ) {
                    Text("Redémarrer")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRebootDialog = false }) {
                    Text("Annuler")
                }
            },
        )
    }
}

// ── Sections privées ──────────────────────────────────────────────────────────

@Composable
private fun StatusSection(
    status: DeviceLocalStatus,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = extendedColors.cardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.xs),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = "Statut du device",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(color = extendedColors.divider)

            StatusRow(
                label = "ID device",
                value = status.deviceId.ifEmpty { "inconnu" },
                contentDescriptionText = "Identifiant du device : ${status.deviceId}",
            )
            StatusRow(
                label = "WireGuard",
                value = status.wgStatus,
                contentDescriptionText = "Statut WireGuard : ${status.wgStatus}",
            )
            StatusRow(
                label = "WiFi",
                value = status.wifiSsid ?: "non connecté",
                contentDescriptionText = "Réseau WiFi : ${status.wifiSsid ?: "non connecté"}",
            )
            StatusRow(
                label = "Uptime",
                value = status.uptime,
                contentDescriptionText = "Temps de fonctionnement : ${status.uptime}",
            )

            if (!status.lastError.isNullOrEmpty()) {
                HorizontalDivider(color = extendedColors.divider)
                Text(
                    text = "Dernière erreur",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = status.lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics {
                        contentDescription = "Dernière erreur : ${status.lastError}"
                    },
                )
            }
        }
    }
}

@Composable
private fun StatusRow(
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
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun WifiSection(
    ssid: String,
    onSsidChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    onConfigure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = extendedColors.cardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.xs),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = "Configuration WiFi",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(color = extendedColors.divider)

            OutlinedTextField(
                value = ssid,
                onValueChange = onSsidChange,
                label = { Text("SSID") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text("Mot de passe") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = onConfigure,
                enabled = ssid.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Configurer le WiFi du device" },
            ) {
                Text("Configurer WiFi")
            }
        }
    }
}

@Composable
private fun WireGuardSection(
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = extendedColors.cardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.xs),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = "WireGuard",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(color = extendedColors.divider)

            OutlinedButton(
                onClick = onRestart,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Redémarrer le tunnel WireGuard du device" },
            ) {
                Text("Redémarrer WireGuard")
            }
        }
    }
}

@Composable
private fun SystemSection(
    onLogs: () -> Unit,
    onReboot: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = LocalExtendedColors.current

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = extendedColors.cardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.xs),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = "Système",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(color = extendedColors.divider)

            OutlinedButton(
                onClick = onLogs,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Récupérer les logs du device" },
            ) {
                Text("Récupérer les logs")
            }

            OutlinedButton(
                onClick = onReboot,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Redémarrer le device" },
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Redémarrer le device")
            }
        }
    }
}
