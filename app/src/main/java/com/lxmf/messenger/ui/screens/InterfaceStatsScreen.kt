package com.lxmf.messenger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lxmf.messenger.util.InterfaceFormattingUtils
import com.lxmf.messenger.viewmodel.InterfaceStatsViewModel
import java.util.Locale

/**
 * Screen displaying detailed statistics and status for a network interface.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterfaceStatsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToEdit: (Long) -> Unit,
    viewModel: InterfaceStatsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    SideEffect {
        Log.d("InterfaceStatsScreen", "🖥️ InterfaceStatsScreen COMPOSING - interface: ${state.interfaceEntity?.name}")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.interfaceEntity?.name ?: "Interface Stats") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            when {
                state.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                state.errorMessage != null -> {
                    ErrorContent(
                        errorMessage = state.errorMessage!!,
                        onBack = onNavigateBack,
                    )
                }
                state.interfaceEntity != null -> {
                    StatsContent(
                        state = state,
                        onToggleEnabled = { viewModel.toggleEnabled() },
                        onEdit = { onNavigateToEdit(state.interfaceEntity!!.id) },
                        onRequestUsbPermission = { viewModel.requestUsbPermission() },
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorContent(
    errorMessage: String,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = errorMessage,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onBack) {
            Text("Go Back")
        }
    }
}

@Composable
private fun StatsContent(
    state: com.lxmf.messenger.viewmodel.InterfaceStatsState,
    onToggleEnabled: () -> Unit,
    onEdit: () -> Unit,
    onRequestUsbPermission: () -> Unit,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Status Card
        StatusCard(
            isEnabled = state.interfaceEntity?.enabled ?: false,
            isOnline = state.isOnline,
            isConnecting = state.isConnecting,
            needsUsbPermission = state.needsUsbPermission,
            onToggleEnabled = onToggleEnabled,
            onRequestUsbPermission = onRequestUsbPermission,
        )

        // Connection Card
        state.interfaceEntity?.let { entity ->
            ConnectionCard(
                interfaceType = entity.type,
                connectionMode = state.connectionMode,
                targetDeviceName = state.targetDeviceName,
                tcpHost = state.tcpHost,
                tcpPort = state.tcpPort,
                usbDeviceId = state.usbDeviceId,
            )
        }

        // RNode Settings Card (only for RNode interfaces)
        if (state.interfaceEntity?.type == "RNode") {
            RNodeSettingsCard(
                frequency = state.frequency,
                bandwidth = state.bandwidth,
                spreadingFactor = state.spreadingFactor,
                txPower = state.txPower,
                codingRate = state.codingRate,
                interfaceMode = state.interfaceMode,
            )
        }

        // Traffic Stats Card
        TrafficStatsCard(
            rxBytes = state.rxBytes,
            txBytes = state.txBytes,
            rssi = state.rssi,
            snr = state.snr,
        )

        // Action Buttons
        OutlinedButton(
            onClick = onEdit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Edit Configuration")
        }
    }
}

@Composable
private fun StatusCard(
    isEnabled: Boolean,
    isOnline: Boolean,
    isConnecting: Boolean,
    needsUsbPermission: Boolean,
    onToggleEnabled: () -> Unit,
    onRequestUsbPermission: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled && isOnline) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Status",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusBadge(
                            text = if (isEnabled) "ENABLED" else "DISABLED",
                            isPositive = isEnabled,
                        )
                        if (isEnabled) {
                            StatusBadge(
                                text = if (isOnline) "ONLINE" else if (isConnecting) "CONNECTING" else "OFFLINE",
                                isPositive = isOnline,
                                showSpinner = isConnecting && !isOnline,
                            )
                        }
                    }
                }
                Switch(
                    checked = isEnabled,
                    onCheckedChange = { onToggleEnabled() },
                )
            }

            // USB Permission message and button
            if (needsUsbPermission && isEnabled && !isOnline) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                    ) {
                        Text(
                            text = "USB permission required",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Grant permission to connect to the USB device",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = onRequestUsbPermission,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Usb,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Grant USB Permission")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(
    text: String,
    isPositive: Boolean,
    showSpinner: Boolean = false,
) {
    val badgeColor = if (isPositive) {
        MaterialTheme.colorScheme.primary
    } else if (showSpinner) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.error
    }

    Surface(
        color = badgeColor.copy(alpha = 0.2f),
        shape = RoundedCornerShape(4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showSpinner) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                    color = badgeColor,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = badgeColor,
            )
        }
    }
}

@Composable
private fun ConnectionCard(
    interfaceType: String,
    connectionMode: String?,
    targetDeviceName: String?,
    tcpHost: String?,
    tcpPort: Int?,
    usbDeviceId: Int?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = "Connection",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Interface type with icon
            val (icon, typeLabel) = InterfaceFormattingUtils.getConnectionIcon(interfaceType, connectionMode)
            StatsInfoRow(
                icon = icon,
                label = "Type",
                value = typeLabel,
            )

            // Connection-specific details
            when {
                connectionMode == "tcp" && tcpHost != null -> {
                    StatsInfoRow(
                        label = "Host",
                        value = "$tcpHost:${tcpPort ?: 7633}",
                    )
                }
                connectionMode == "usb" && usbDeviceId != null -> {
                    StatsInfoRow(
                        label = "USB Device",
                        value = "ID: $usbDeviceId",
                    )
                }
                targetDeviceName != null && targetDeviceName.isNotBlank() -> {
                    StatsInfoRow(
                        label = "Device",
                        value = targetDeviceName,
                    )
                }
                interfaceType == "TCPClient" && tcpHost != null -> {
                    StatsInfoRow(
                        label = "Server",
                        value = "$tcpHost:${tcpPort ?: 4242}",
                    )
                }
                interfaceType == "TCPServer" && tcpPort != null -> {
                    StatsInfoRow(
                        label = "Listen Port",
                        value = tcpPort.toString(),
                    )
                }
            }
        }
    }
}

@Composable
private fun RNodeSettingsCard(
    frequency: Long?,
    bandwidth: Int?,
    spreadingFactor: Int?,
    txPower: Int?,
    codingRate: Int?,
    interfaceMode: String?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = "Radio Settings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(12.dp))

            frequency?.let {
                StatsInfoRow(label = "Frequency", value = InterfaceFormattingUtils.formatFrequency(it))
            }
            bandwidth?.let {
                StatsInfoRow(label = "Bandwidth", value = InterfaceFormattingUtils.formatBandwidth(it))
            }
            spreadingFactor?.let {
                StatsInfoRow(label = "Spreading Factor", value = "SF$it")
            }
            codingRate?.let {
                StatsInfoRow(label = "Coding Rate", value = "4/$it")
            }
            txPower?.let {
                StatsInfoRow(label = "TX Power", value = "$it dBm")
            }
            interfaceMode?.let {
                StatsInfoRow(label = "Mode", value = it.replaceFirstChar { c -> c.uppercase() })
            }
        }
    }
}

@Composable
private fun TrafficStatsCard(
    rxBytes: Long,
    txBytes: Long,
    rssi: Int?,
    snr: Float?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = "Traffic Statistics",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                StatBox(
                    label = "Received",
                    value = InterfaceFormattingUtils.formatBytes(rxBytes),
                )
                StatBox(
                    label = "Transmitted",
                    value = InterfaceFormattingUtils.formatBytes(txBytes),
                )
            }

            if (rssi != null || snr != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    rssi?.let {
                        StatBox(
                            label = "RSSI",
                            value = "$it dBm",
                        )
                    }
                    snr?.let {
                        StatBox(
                            label = "SNR",
                            value = String.format(Locale.US, "%.1f dB", it),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatBox(
    label: String,
    value: String,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatsInfoRow(
    label: String,
    value: String,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

