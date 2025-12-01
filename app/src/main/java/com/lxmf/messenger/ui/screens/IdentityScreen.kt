package com.lxmf.messenger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.lxmf.messenger.data.model.SignalQuality
import com.lxmf.messenger.ui.components.QrCodeImage
import com.lxmf.messenger.util.IdentityQrCodeUtils
import com.lxmf.messenger.viewmodel.BleConnectionsUiState
import com.lxmf.messenger.viewmodel.DebugInfo
import com.lxmf.messenger.viewmodel.DebugViewModel
import com.lxmf.messenger.viewmodel.InterfaceInfo
import com.lxmf.messenger.viewmodel.TestAnnounceResult

/**
 * Network Status Screen
 * Shows network monitoring information: BLE connections, interfaces, Reticulum status, test tools.
 * Identity features moved to MyIdentityScreen for clear separation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentityScreen(
    onBackClick: () -> Unit = {},
    viewModel: DebugViewModel = hiltViewModel(),
    bleConnectionsViewModel: com.lxmf.messenger.viewmodel.BleConnectionsViewModel = hiltViewModel(),
    onNavigateToBleStatus: () -> Unit = {},
) {
    val debugInfo by viewModel.debugInfo.collectAsState()
    val networkStatus by viewModel.networkStatus.collectAsState()
    val testResult by viewModel.testAnnounceResult.collectAsState()
    val bleConnectionsState by bleConnectionsViewModel.uiState.collectAsState()
    val isRestarting by viewModel.isRestarting.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network Status") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .consumeWindowInsets(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // BLE Connections Card
            BleConnectionsCard(
                uiState = bleConnectionsState,
                onViewDetails = onNavigateToBleStatus,
            )

            // Status Card
            StatusCard(
                initialized = debugInfo.initialized,
                networkStatus = networkStatus,
                error = debugInfo.error,
            )

            // Service Control Card
            ServiceControlCard(
                onShutdown = { viewModel.shutdownService() },
                onRestart = { viewModel.restartService() },
            )

            // Interfaces Card
            InterfacesCard(interfaces = debugInfo.interfaces)

            // Test Actions Card
            TestActionsCard(
                onTestAnnounce = { viewModel.createTestAnnounce() },
                testResult = testResult,
                onClearResult = { viewModel.clearTestResult() },
            )

            // Reticulum Info Card
            ReticulumInfoCard(debugInfo = debugInfo)

            // Bottom spacing for navigation bar (fixed height since M3 NavigationBar consumes the insets)
            Spacer(modifier = Modifier.height(100.dp))
        }
    }

    // Service Restart Dialog
    if (isRestarting) {
        ServiceRestartDialog()
    }
}

@Composable
fun StatusCard(
    initialized: Boolean,
    networkStatus: String,
    error: String?,
) {
    val isConnecting = networkStatus == "CONNECTING"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    when {
                        isConnecting -> MaterialTheme.colorScheme.tertiaryContainer
                        initialized && error == null -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.errorContainer
                    },
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector =
                        when {
                            isConnecting -> Icons.Default.Refresh
                            initialized && error == null -> Icons.Default.CheckCircle
                            else -> Icons.Default.Warning
                        },
                    contentDescription = null,
                    tint =
                        when {
                            isConnecting -> MaterialTheme.colorScheme.onTertiaryContainer
                            initialized && error == null -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onErrorContainer
                        },
                )
                Text(
                    text = "Reticulum Status",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Divider()

            InfoRow(label = "Initialized", value = if (initialized) "Yes" else "No")
            InfoRow(label = "Network Status", value = networkStatus)

            if (isConnecting) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = "Reconnecting to service...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }

            if (error != null) {
                Text(
                    text = "Error: $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                                RoundedCornerShape(8.dp),
                            )
                            .padding(8.dp),
                )
            }
        }
    }
}

@Composable
fun ReticulumInfoCard(debugInfo: DebugInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Reticulum Information",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            Divider()

            InfoRow(label = "RNS Available", value = if (debugInfo.reticulumAvailable) "Yes" else "No")
            InfoRow(label = "Storage Path", value = debugInfo.storagePath, monospace = true)
            InfoRow(label = "Transport Enabled", value = if (debugInfo.transportEnabled) "Yes" else "No")
            InfoRow(label = "Multicast Lock", value = if (debugInfo.multicastLockHeld) "✓ Held" else "✗ Not held")
            InfoRow(label = "WiFi Lock", value = if (debugInfo.wifiLockHeld) "✓ Held" else "✗ Not held")
            InfoRow(label = "Wake Lock", value = if (debugInfo.wakeLockHeld) "✓ Held" else "✗ Not held")
        }
    }
}

@Composable
fun InterfacesCard(interfaces: List<InterfaceInfo>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Network Interfaces (${interfaces.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            Divider()

            if (interfaces.isEmpty()) {
                Text(
                    text = "No interfaces configured",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                interfaces.forEach { iface ->
                    InterfaceRow(iface)
                }
            }
        }
    }
}

@Composable
fun InterfaceRow(iface: InterfaceInfo) {
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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = iface.type,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = iface.name,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Icon(
            imageVector = if (iface.online) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = if (iface.online) "Online" else "Offline",
            tint =
                if (iface.online) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
        )
    }
}

@Composable
fun TestActionsCard(
    onTestAnnounce: () -> Unit,
    testResult: TestAnnounceResult?,
    onClearResult: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Test Actions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            Divider()

            Button(
                onClick = onTestAnnounce,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Send, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Send Test Announce")
            }

            if (testResult != null) {
                if (testResult.success) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    "Test announce sent!",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            if (testResult.hexHash != null) {
                                Text(
                                    "Hash: ${testResult.hexHash}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            TextButton(onClick = onClearResult) {
                                Text("Dismiss")
                            }
                        }
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    "Error sending announce",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            if (testResult.error != null) {
                                Text(
                                    testResult.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            TextButton(onClick = onClearResult) {
                                Text("Dismiss")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UserIdentityCard(
    displayName: String,
    identityHash: String?,
    destinationHash: String?,
    onClick: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = "Your Identity",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Default.QrCode,
                    contentDescription = "View QR Code",
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }

            Divider()

            InfoRow(label = "Display Name", value = displayName)

            if (destinationHash != null) {
                InfoRow(
                    label = "Destination",
                    value =
                        IdentityQrCodeUtils.formatHashForDisplay(
                            hash = destinationHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
                        ),
                    monospace = true,
                )
            }

            Text(
                text = "Tap to view full identity details and QR code for sharing",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun InfoRow(
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

@Composable
fun BleConnectionsCard(
    uiState: BleConnectionsUiState,
    onViewDetails: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "BLE Connections",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            Divider()

            when (uiState) {
                is BleConnectionsUiState.Loading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Loading connections...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is BleConnectionsUiState.Success -> {
                    if (uiState.totalConnections == 0) {
                        Text(
                            text = "No active BLE connections",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        // Summary stats
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(8.dp),
                                    )
                                    .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = uiState.totalConnections.toString(),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "Total",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = uiState.centralConnections.toString(),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "Central",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = uiState.peripheralConnections.toString(),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "Peripheral",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        // Signal quality indicator
                        val avgSignalQuality =
                            if (uiState.connections.isNotEmpty()) {
                                val avgRssi = uiState.connections.map { it.rssi }.average().toInt()
                                when {
                                    avgRssi > -50 -> SignalQuality.EXCELLENT
                                    avgRssi > -70 -> SignalQuality.GOOD
                                    avgRssi > -85 -> SignalQuality.FAIR
                                    else -> SignalQuality.POOR
                                }
                            } else {
                                SignalQuality.GOOD
                            }

                        val (signalText, signalColor) =
                            when (avgSignalQuality) {
                                SignalQuality.EXCELLENT -> "Excellent Signal" to MaterialTheme.colorScheme.primary
                                SignalQuality.GOOD -> "Good Signal" to MaterialTheme.colorScheme.primary
                                SignalQuality.FAIR -> "Fair Signal" to MaterialTheme.colorScheme.tertiary
                                SignalQuality.POOR -> "Poor Signal" to MaterialTheme.colorScheme.error
                            }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = signalColor,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = signalText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = signalColor,
                                )
                            }
                            TextButton(onClick = onViewDetails) {
                                Text("View Details")
                                Icon(
                                    imageVector = Icons.Default.ArrowForward,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }

                is BleConnectionsUiState.Error -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Error: ${uiState.message}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                is BleConnectionsUiState.PermissionsRequired -> {
                    Text(
                        text = "Bluetooth permissions required",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is BleConnectionsUiState.BluetoothDisabled -> {
                    Text(
                        text = "Bluetooth is turned off",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Full-screen dialog showing complete identity details and QR code.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentityDetailsDialog(
    displayName: String,
    identityHash: String?,
    destinationHash: String?,
    qrCodeData: String?,
    onDismiss: () -> Unit,
    onShareClick: () -> Unit,
    onNavigateToQrScanner: () -> Unit = {},
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Your Identity") },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                )
                            }
                        },
                        colors =
                            TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                    )
                },
            ) { paddingValues ->
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    // Display Name
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    // QR Code
                    if (qrCodeData != null) {
                        QrCodeImage(
                            data = qrCodeData,
                            size = 280.dp,
                        )

                        Text(
                            text = "Scan this QR code to add me as a contact",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Action Buttons Row
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // Share Button
                        Button(
                            onClick = onShareClick,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Share")
                        }

                        // Scan QR Button
                        OutlinedButton(
                            onClick = onNavigateToQrScanner,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Scan")
                        }
                    }

                    Divider()

                    // Identity Hash
                    if (identityHash != null) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "Identity Hash",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = identityHash,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(identityHash))
                                        },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = "Copy",
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Destination Hash
                    if (destinationHash != null) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "Destination Hash (LXMF)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = destinationHash,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(destinationHash))
                                        },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = "Copy",
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}

/**
 * Service control card with shutdown and restart buttons.
 */
@Composable
private fun ServiceControlCard(
    onShutdown: () -> Unit,
    onRestart: () -> Unit,
) {
    var showShutdownDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.List,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Service Control",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                text = "Manually stop or restart the background Reticulum service.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { showShutdownDialog = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Shutdown")
                }

                Button(
                    onClick = onRestart,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Send, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Restart")
                }
            }
        }
    }

    // Confirmation dialog
    if (showShutdownDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showShutdownDialog = false },
            title = { Text("Shutdown Service?") },
            text = {
                Text(
                    "This will stop the background Reticulum service. You will not receive messages until you restart the app or manually restart the service.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showShutdownDialog = false
                        onShutdown()
                    },
                ) {
                    Text("Shutdown")
                }
            },
            dismissButton = {
                TextButton(onClick = { showShutdownDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

/**
 * Blocking dialog shown while restarting the Reticulum service.
 */
@Composable
private fun ServiceRestartDialog() {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { /* Cannot dismiss - blocking */ },
        icon = {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
            )
        },
        title = { Text("Restarting Service") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Restarting Reticulum network...",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "This may take a few seconds",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { /* No buttons - blocking */ },
    )
}
