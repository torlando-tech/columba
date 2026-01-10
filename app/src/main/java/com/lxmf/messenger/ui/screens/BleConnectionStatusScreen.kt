package com.lxmf.messenger.ui.screens

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lxmf.messenger.data.model.BleConnectionInfo
import com.lxmf.messenger.data.model.ConnectionType
import com.lxmf.messenger.data.model.SignalQuality
import com.lxmf.messenger.ui.components.BluetoothPermissionController
import com.lxmf.messenger.ui.components.PermissionDeniedCard
import com.lxmf.messenger.ui.components.rememberBluetoothPermissionController
import com.lxmf.messenger.viewmodel.BleConnectionsUiState
import com.lxmf.messenger.viewmodel.BleConnectionsViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleConnectionStatusScreen(
    onBackClick: () -> Unit,
    viewModel: BleConnectionsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    // Start/stop periodic refresh when screen is visible/hidden
    DisposableEffect(viewModel) {
        viewModel.startPeriodicRefresh()
        onDispose {
            viewModel.stopPeriodicRefresh()
        }
    }

    // Bluetooth enable launcher
    val bluetoothEnableLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            // Bluetooth state will be updated automáticamente via flow
            Log.d("BleConnectionStatus", "Bluetooth enable result: ${result.resultCode}")
        }

    val btController: BluetoothPermissionController =
        rememberBluetoothPermissionController(
            onEnableRequested = { _ ->
                viewModel.getEnableBluetoothIntent()?.let { intent ->
                    bluetoothEnableLauncher.launch(intent)
                }
            },
            onOpenSettingsRequested = { ctx ->
                val intent = viewModel.getBluetoothSettingsIntent()
                ctx.startActivity(intent)
            },
        )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BLE Connections") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            )
        },
    ) { paddingValues ->
        when (val state = uiState) {
            is BleConnectionsUiState.Loading -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Loading connections...")
                    }
                }
            }

            is BleConnectionsUiState.BluetoothDisabled -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.BluetoothDisabled,
                            contentDescription = null,
                            modifier = Modifier.size(80.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Bluetooth is turned off",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Turn on Bluetooth to see BLE connections",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        // Button to enable Bluetooth
                        Button(
                            onClick = btController.onEnableClick,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bluetooth,
                                contentDescription = null,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Turn ON")
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors =
                                CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                ),
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Text(
                                    text = "Enable Bluetooth to discover and connect to nearby BLE peers.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }
                }
            }

            is BleConnectionsUiState.Success -> {
                if (state.connections.isEmpty()) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(paddingValues),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.BluetoothConnected,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = "Bluetooth is turned on",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "No active connections",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "BLE peers will appear here when connected",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            // Button to open Bluetooth settings
                            OutlinedButton(
                                onClick = btController.onOpenSettingsClick,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = null,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Bluetooth Settings")
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(paddingValues)
                                .consumeWindowInsets(paddingValues),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Summary header
                        item {
                            SummaryCard(
                                totalConnections = state.totalConnections,
                                centralConnections = state.centralConnections,
                                peripheralConnections = state.peripheralConnections,
                            )
                        }

                        // Connection cards
                        items(
                            items = state.connections,
                            // Use MAC address as key (always unique)
                            key = { it.currentMac },
                        ) { connection ->
                            ConnectionCard(
                                connection = connection,
                                onDisconnect = { viewModel.disconnectPeer(connection.currentMac) },
                            )
                        }

                        // Bottom spacer
                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                }
            }

            is BleConnectionsUiState.Error -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = "Error Loading Connections",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = { viewModel.refresh() }) {
                            Text("Retry")
                        }
                    }
                }
            }

            is BleConnectionsUiState.PermissionsRequired -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    PermissionDeniedCard()
                }
            }
        }
    }
}

@Composable
fun SummaryCard(
    totalConnections: Int,
    centralConnections: Int,
    peripheralConnections: Int,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("summary_card"),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            SummaryItem(
                label = "Total",
                value = totalConnections.toString(),
                icon = Icons.Default.Bluetooth,
            )
            SummaryItem(
                label = "Central",
                value = centralConnections.toString(),
                icon = Icons.Default.BluetoothConnected,
            )
            SummaryItem(
                label = "Peripheral",
                value = peripheralConnections.toString(),
                icon = Icons.Default.BluetoothSearching,
            )
        }
    }
}

@Composable
fun SummaryItem(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
fun ConnectionCard(
    connection: BleConnectionInfo,
    onDisconnect: () -> Unit,
) {
    // Live-updating duration: recalculate every second based on connectedSince timestamp
    val currentTimeMs = remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            currentTimeMs.longValue = System.currentTimeMillis()
        }
    }

    // Calculate duration from connectedAt (or use fallback to connectionDurationMs)
    val liveDurationMs =
        if (connection.connectedAt > 0) {
            currentTimeMs.longValue - connection.connectedAt
        } else {
            connection.connectionDurationMs
        }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("connection_card_${connection.currentMac}"),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header: Name and Identity
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = connection.peerName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = connection.identityHash,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Connection type badge
                ConnectionTypeBadge(connection.connectionType)
            }

            HorizontalDivider()

            // Signal strength
            SignalStrengthRow(
                rssi = connection.rssi,
                signalQuality = connection.signalQuality,
            )

            // Connection details
            ConnectionDetailRow(label = "MAC Address", value = connection.currentMac, monospace = true)
            ConnectionDetailRow(label = "MTU", value = "${connection.mtu} bytes")
            ConnectionDetailRow(
                label = "Connected",
                value = formatDuration(liveDurationMs),
            )
            ConnectionDetailRow(
                label = "First Seen",
                value = formatTimestamp(connection.firstSeen),
            )
            ConnectionDetailRow(
                label = "Last Seen",
                value = formatTimestamp(connection.lastSeen),
            )

            // Performance metrics (when available)
            if (connection.bytesReceived > 0 || connection.bytesSent > 0) {
                HorizontalDivider()
                Text(
                    text = "Performance",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("performance_section_${connection.currentMac}"),
                )
                ConnectionDetailRow(
                    label = "Data Sent",
                    value = formatBytes(connection.bytesSent),
                )
                ConnectionDetailRow(
                    label = "Data Received",
                    value = formatBytes(connection.bytesReceived),
                )
                if (connection.successRate > 0) {
                    ConnectionDetailRow(
                        label = "Success Rate",
                        value = "${(connection.successRate * 100).toInt()}%",
                    )
                }
            }

            // Disconnect button
            OutlinedButton(
                onClick = onDisconnect,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("disconnect_button_${connection.currentMac}"),
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                Icon(Icons.Default.BluetoothDisabled, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Disconnect")
            }
        }
    }
}

@Composable
fun ConnectionTypeBadge(type: ConnectionType) {
    val (text, color) =
        when (type) {
            ConnectionType.BOTH -> "Both" to MaterialTheme.colorScheme.primary
            ConnectionType.CENTRAL -> "Central" to MaterialTheme.colorScheme.secondary
            ConnectionType.PERIPHERAL -> "Peripheral" to MaterialTheme.colorScheme.tertiary
        }

    Surface(
        color = color.copy(alpha = 0.2f),
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun SignalStrengthRow(
    rssi: Int,
    signalQuality: SignalQuality,
) {
    // RSSI of -100 is used as a placeholder when not available (peripheral connections)
    val rssiUnavailable = rssi <= -100

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(8.dp),
                )
                .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (icon, text, color) =
                if (rssiUnavailable) {
                    Triple(
                        Icons.Default.Info,
                        "N/A",
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    when (signalQuality) {
                        SignalQuality.EXCELLENT ->
                            Triple(
                                Icons.Default.CheckCircle,
                                "Excellent",
                                MaterialTheme.colorScheme.primary,
                            )
                        SignalQuality.GOOD ->
                            Triple(
                                Icons.Default.Check,
                                "Good",
                                MaterialTheme.colorScheme.primary,
                            )
                        SignalQuality.FAIR ->
                            Triple(
                                Icons.Default.Warning,
                                "Fair",
                                MaterialTheme.colorScheme.tertiary,
                            )
                        SignalQuality.POOR ->
                            Triple(
                                Icons.Default.Error,
                                "Poor",
                                MaterialTheme.colorScheme.error,
                            )
                    }
                }

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "Signal Strength",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = color,
                )
            }
        }

        Text(
            text = if (rssiUnavailable) "—" else "$rssi dBm",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun ConnectionDetailRow(
    label: String,
    value: String,
    monospace: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            fontWeight = FontWeight.Medium,
        )
    }
}

// Helper functions
private fun formatDuration(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24

    return when {
        days > 0 -> "${days}d ${hours % 24}h"
        hours > 0 -> "${hours}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_000_000 -> String.format(Locale.US, "%.2f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format(Locale.US, "%.2f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }
}
