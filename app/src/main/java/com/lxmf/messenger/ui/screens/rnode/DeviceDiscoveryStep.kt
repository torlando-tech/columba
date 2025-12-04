package com.lxmf.messenger.ui.screens.rnode

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.data.model.BluetoothType
import com.lxmf.messenger.data.model.DiscoveredRNode
import com.lxmf.messenger.reticulum.ble.util.BlePermissionManager
import com.lxmf.messenger.viewmodel.RNodeWizardViewModel

@Composable
fun DeviceDiscoveryStep(viewModel: RNodeWizardViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (permissions.values.all { it }) {
            viewModel.startDeviceScan()
        }
    }

    // Auto-start scan when entering this step (if not in edit mode with device already selected)
    LaunchedEffect(Unit) {
        if (state.selectedDevice == null || !state.isEditMode) {
            val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                )
            } else {
                arrayOf(
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                )
            }

            if (BlePermissionManager.hasAllPermissions(context)) {
                viewModel.startDeviceScan()
            } else {
                permissionLauncher.launch(requiredPermissions)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        // Scanning indicator
        if (state.isScanning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.BluetoothSearching,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Scanning for RNode devices...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        // Error message
        state.scanError?.let { error ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = error,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        // Pairing error
        state.pairingError?.let { error ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.clearPairingError() }) {
                        Text("Dismiss")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Current device (in edit mode)
        if (state.isEditMode && state.selectedDevice != null && !state.showManualEntry) {
            Text(
                "Current Device",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            BluetoothDeviceCard(
                device = state.selectedDevice!!,
                isSelected = true,
                onSelect = { },
                onPair = { },
                isPairingInProgress = false,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Or select a different device:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }

        // Discovered devices list
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(
                items = state.discoveredDevices.filter {
                    // In edit mode, don't show the current device in the list
                    !(state.isEditMode && it.name == state.selectedDevice?.name)
                },
                key = { it.address },
            ) { device ->
                BluetoothDeviceCard(
                    device = device,
                    isSelected = state.selectedDevice?.address == device.address,
                    onSelect = { viewModel.selectDevice(device) },
                    onPair = { viewModel.initiateBluetoothPairing(device) },
                    isPairingInProgress = state.isPairingInProgress,
                )
            }

            // Manual entry option
            item {
                OutlinedCard(
                    onClick = { viewModel.showManualEntry() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                "Enter device manually",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "If your device isn't listed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // Rescan button (inside LazyColumn so it scrolls with content)
            if (!state.isScanning && !state.showManualEntry) {
                item {
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { viewModel.startDeviceScan() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Scan Again")
                    }
                }
            }

            // Bottom spacing for navigation bar
            item {
                Spacer(modifier = Modifier.height(100.dp))
            }
        }

        // Manual entry form
        AnimatedVisibility(visible = state.showManualEntry) {
            Column {
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = state.manualDeviceName,
                    onValueChange = { viewModel.updateManualDeviceName(it) },
                    label = { Text("Bluetooth Device Name") },
                    placeholder = { Text("e.g., RNode 1234") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Default.Bluetooth, contentDescription = null)
                    },
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    "Connection Type",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.manualBluetoothType == BluetoothType.CLASSIC,
                        onClick = { viewModel.updateManualBluetoothType(BluetoothType.CLASSIC) },
                        label = { Text("Bluetooth Classic") },
                        leadingIcon = if (state.manualBluetoothType == BluetoothType.CLASSIC) {
                            { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                    )
                    FilterChip(
                        selected = state.manualBluetoothType == BluetoothType.BLE,
                        onClick = { viewModel.updateManualBluetoothType(BluetoothType.BLE) },
                        label = { Text("Bluetooth LE") },
                        leadingIcon = if (state.manualBluetoothType == BluetoothType.BLE) {
                            { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                    )
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    "Most RNodes use Bluetooth Classic. Only select BLE if your RNode specifically supports it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(8.dp))

                TextButton(onClick = { viewModel.hideManualEntry() }) {
                    Text("Cancel manual entry")
                }
            }
        }
    }
}

@Composable
private fun BluetoothDeviceCard(
    device: DiscoveredRNode,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onPair: () -> Unit,
    isPairingInProgress: Boolean,
) {
    Card(
        onClick = onSelect,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        device.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Bluetooth type badge
                        Text(
                            when (device.type) {
                                BluetoothType.CLASSIC -> "Classic"
                                BluetoothType.BLE -> "BLE"
                                BluetoothType.UNKNOWN -> "Unknown"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )

                        // Signal strength (BLE only)
                        device.rssi?.let { rssi ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.SignalCellular4Bar,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                Spacer(Modifier.width(2.dp))
                                Text(
                                    "${rssi}dBm",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }

                        // Paired status
                        if (device.isPaired) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Link,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                Spacer(Modifier.width(2.dp))
                                Text(
                                    "Paired",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // Selection indicator or pair button
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else if (!device.isPaired) {
                if (isPairingInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    TextButton(onClick = onPair) {
                        Text("Pair")
                    }
                }
            }
        }
    }
}
