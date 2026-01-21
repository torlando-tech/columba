package com.lxmf.messenger.ui.screens.rnode

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.data.model.BluetoothType
import com.lxmf.messenger.data.model.DiscoveredRNode
import com.lxmf.messenger.data.model.DiscoveredUsbDevice
import com.lxmf.messenger.reticulum.ble.util.BlePermissionManager
import com.lxmf.messenger.viewmodel.RNodeConnectionType
import com.lxmf.messenger.viewmodel.RNodeWizardState
import com.lxmf.messenger.viewmodel.RNodeWizardViewModel

@Composable
fun DeviceDiscoveryStep(viewModel: RNodeWizardViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // Permission launcher
    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            if (permissions.values.all { it }) {
                viewModel.startDeviceScan()
            }
        }

    // Companion Device Association launcher (Android 12+)
    val associationLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartIntentSenderForResult(),
        ) { result ->
            if (result.resultCode != Activity.RESULT_OK) {
                viewModel.onAssociationCancelled()
            }
            // If RESULT_OK, the CDM callback onAssociationCreated will handle the selection
        }

    // Launch the association intent when provided by ViewModel
    LaunchedEffect(state.pendingAssociationIntent) {
        state.pendingAssociationIntent?.let { intentSender ->
            val request = IntentSenderRequest.Builder(intentSender).build()
            associationLauncher.launch(request)
            viewModel.onAssociationIntentLaunched()
        }
    }

    // Auto-start scan when entering this step in Bluetooth mode
    LaunchedEffect(state.connectionType) {
        if (state.connectionType == RNodeConnectionType.BLUETOOTH) {
            val requiredPermissions =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
    ) {
        // Connection type selector
        Text(
            "Connection Method",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.connectionType == RNodeConnectionType.BLUETOOTH,
                onClick = { viewModel.setConnectionType(RNodeConnectionType.BLUETOOTH) },
                label = { Text("Bluetooth") },
                leadingIcon = {
                    Icon(Icons.Default.Bluetooth, contentDescription = null, Modifier.size(18.dp))
                },
            )
            FilterChip(
                selected = state.connectionType == RNodeConnectionType.TCP_WIFI,
                onClick = { viewModel.setConnectionType(RNodeConnectionType.TCP_WIFI) },
                label = { Text("WiFi / TCP") },
                leadingIcon = {
                    Icon(Icons.Default.Wifi, contentDescription = null, Modifier.size(18.dp))
                },
            )
            FilterChip(
                selected = state.connectionType == RNodeConnectionType.USB_SERIAL,
                onClick = { viewModel.setConnectionType(RNodeConnectionType.USB_SERIAL) },
                label = { Text("USB") },
                leadingIcon = {
                    Icon(Icons.Default.Usb, contentDescription = null, Modifier.size(18.dp))
                },
            )
        }
        Spacer(Modifier.height(16.dp))

        // Show content based on connection type
        when (state.connectionType) {
            RNodeConnectionType.TCP_WIFI -> {
                TcpConnectionForm(
                    tcpHost = state.tcpHost,
                    isValidating = state.isTcpValidating,
                    validationSuccess = state.tcpValidationSuccess,
                    validationError = state.tcpValidationError,
                    onHostChange = { viewModel.updateTcpHost(it) },
                    onTestConnection = { viewModel.validateTcpConnection() },
                )
            }
            RNodeConnectionType.BLUETOOTH -> {
                BluetoothDeviceDiscovery(
                    viewModel = viewModel,
                    state = state,
                )
            }
            RNodeConnectionType.USB_SERIAL -> {
                UsbDeviceDiscovery(
                    viewModel = viewModel,
                    state = state,
                )
            }
        }
    }
}

@Composable
private fun TcpConnectionForm(
    tcpHost: String,
    isValidating: Boolean,
    validationSuccess: Boolean?,
    validationError: String?,
    onHostChange: (String) -> Unit,
    onTestConnection: () -> Unit,
) {
    Column {
        Text(
            "Connect to an RNode device over WiFi/TCP (port 7633).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = tcpHost,
            onValueChange = onHostChange,
            label = { Text("IP Address or Hostname") },
            placeholder = { Text("e.g., 10.0.0.1 or rnode.local") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = {
                Icon(Icons.Default.Wifi, contentDescription = null)
            },
        )

        Spacer(Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onTestConnection,
                enabled = tcpHost.isNotBlank() && !isValidating,
            ) {
                if (isValidating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("Test Connection")
            }

            // Validation result
            when {
                validationSuccess == true -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Success",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Connected",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                validationSuccess == false -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = "Failed",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Failed",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        // Error message
        validationError?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            "The RNode must be connected to the same network and have WiFi/TCP enabled. " +
                "Default port is 7633.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BluetoothDeviceDiscovery(
    viewModel: RNodeWizardViewModel,
    state: RNodeWizardState,
) {
    Column {
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
                colors =
                    CardDefaults.cardColors(
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

        // Pairing error with retry button
        state.pairingError?.let { error ->
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TextButton(onClick = { viewModel.clearPairingError() }) {
                            Text("Dismiss")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { viewModel.retryPairing() }) {
                            Text("Retry")
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Pairing in progress indicator
        if (state.isPairingInProgress && !state.isUsbAssistedPairingActive) {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Pairing...",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Enter the PIN code shown on your RNode display",
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // USB-assisted pairing progress/device selection
        if (state.isUsbAssistedPairingActive) {
            UsbAssistedPairingCard(
                devices = state.usbAssistedPairingDevices,
                pin = state.usbAssistedPairingPin,
                status = state.usbAssistedPairingStatus,
                isPairing = state.isPairingInProgress,
                onDeviceSelected = { viewModel.selectDeviceForUsbPairing(it) },
                onCancel = { viewModel.cancelUsbAssistedPairing() },
            )
            Spacer(Modifier.height(16.dp))
        }

        // Waiting for device to reconnect (after reboot)
        if (state.isWaitingForReconnect) {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Waiting for RNode to reconnect...",
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    state.reconnectDeviceName?.let { name ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Looking for: $name",
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { viewModel.cancelReconnectScan() }) {
                            Text("Cancel")
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Association error
        state.associationError?.let { error ->
            Card(
                colors =
                    CardDefaults.cardColors(
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
                    TextButton(onClick = { viewModel.clearAssociationError() }) {
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
                onSetType = { viewModel.setDeviceType(state.selectedDevice!!, it) },
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
                items =
                    state.discoveredDevices.filter {
                        // In edit mode, don't show the current device in the list
                        !(state.isEditMode && it.name == state.selectedDevice?.name)
                    },
                key = { it.address },
            ) { device ->
                BluetoothDeviceCard(
                    device = device,
                    isSelected = state.selectedDevice?.address == device.address,
                    onSelect = {
                        // Use CompanionDeviceManager for official association on Android 12+
                        viewModel.requestDeviceAssociation(device) {
                            // Fallback for older Android - direct selection
                            viewModel.selectDevice(device)
                        }
                    },
                    onPair = { viewModel.initiateBluetoothPairing(device) },
                    onSetType = { viewModel.setDeviceType(device, it) },
                    isPairingInProgress = state.isPairingInProgress,
                    isAssociating = state.isAssociating,
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

            // USB-assisted pairing section
            if (!state.showManualEntry) {
                item {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Having trouble pairing?",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                item {
                    OutlinedCard(
                        onClick = { viewModel.startUsbAssistedPairing() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isUsbAssistedPairingActive,
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Usb,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Pair via USB",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "Connect RNode via USB to securely configure Bluetooth pairing",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (state.isUsbAssistedPairingActive) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
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
                    isError = state.manualDeviceNameError != null,
                    supportingText = {
                        val error = state.manualDeviceNameError
                        val warning = state.manualDeviceNameWarning
                        when {
                            error != null ->
                                Text(
                                    error,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            warning != null ->
                                Text(
                                    warning,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                        }
                    },
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
                        leadingIcon =
                            if (state.manualBluetoothType == BluetoothType.CLASSIC) {
                                { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(18.dp)) }
                            } else {
                                null
                            },
                    )
                    FilterChip(
                        selected = state.manualBluetoothType == BluetoothType.BLE,
                        onClick = { viewModel.updateManualBluetoothType(BluetoothType.BLE) },
                        label = { Text("Bluetooth LE") },
                        leadingIcon =
                            if (state.manualBluetoothType == BluetoothType.BLE) {
                                { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(18.dp)) }
                            } else {
                                null
                            },
                    )
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    "ESP32-S3 and nRF52840 devices support BLE. ESP32 (original) devices support Bluetooth Classic.",
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
    onSetType: (BluetoothType) -> Unit,
    isPairingInProgress: Boolean,
    isAssociating: Boolean = false,
) {
    var showTypeSelector by remember { mutableStateOf(false) }

    Card(
        onClick = {
            if (device.type == BluetoothType.UNKNOWN) {
                showTypeSelector = !showTypeSelector
            } else if (!device.isPaired) {
                onPair()
            } else {
                onSelect()
            }
        },
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else if (device.type == BluetoothType.UNKNOWN) {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier =
                    Modifier
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
                        tint =
                            if (isSelected) {
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
                            color =
                                if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Bluetooth type badge with warning for UNKNOWN
                            if (device.type == BluetoothType.UNKNOWN) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = "Unknown type",
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                    Text(
                                        "Unknown type",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            } else {
                                Text(
                                    when (device.type) {
                                        BluetoothType.CLASSIC -> "Classic"
                                        BluetoothType.BLE -> "BLE"
                                        BluetoothType.UNKNOWN -> "Unknown"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color =
                                        if (isSelected) {
                                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                )
                            }

                            // Signal strength (BLE only)
                            device.rssi?.let { rssi ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.SignalCellular4Bar,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint =
                                            if (isSelected) {
                                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                    )
                                    Spacer(Modifier.width(2.dp))
                                    Text(
                                        "${rssi}dBm",
                                        style = MaterialTheme.typography.labelSmall,
                                        color =
                                            if (isSelected) {
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
                                        tint =
                                            if (isSelected) {
                                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                    )
                                    Spacer(Modifier.width(2.dp))
                                    Text(
                                        "Paired",
                                        style = MaterialTheme.typography.labelSmall,
                                        color =
                                            if (isSelected) {
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

                // Selection indicator, association progress, or pair button
                when {
                    isSelected -> {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    isAssociating -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    !device.isPaired -> {
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

            // Type selector for UNKNOWN devices
            AnimatedVisibility(visible = device.type == BluetoothType.UNKNOWN && showTypeSelector) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp),
                ) {
                    Text(
                        "Select connection type:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = false,
                            onClick = {
                                onSetType(BluetoothType.CLASSIC)
                                showTypeSelector = false
                            },
                            label = { Text("Bluetooth Classic") },
                        )
                        FilterChip(
                            selected = false,
                            onClick = {
                                onSetType(BluetoothType.BLE)
                                showTypeSelector = false
                            },
                            label = { Text("Bluetooth LE") },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Power on device and rescan to auto-detect type.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun UsbDeviceDiscovery(
    viewModel: RNodeWizardViewModel,
    state: RNodeWizardState,
) {
    Column {
        Text(
            "Connect RNode via USB cable. Supports FTDI, CP210x, CH340, and CDC-ACM devices.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        // Scanning indicator
        if (state.isUsbScanning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Scanning for USB devices...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        // Error message
        state.usbScanError?.let { error ->
            Card(
                colors =
                    CardDefaults.cardColors(
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
                    TextButton(onClick = { viewModel.clearUsbError() }) {
                        Text("Dismiss")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Bluetooth pairing mode card
        if (state.isUsbPairingMode) {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Bluetooth Pairing Mode",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(8.dp))

                    if (state.usbBluetoothPin != null) {
                        Text(
                            "PIN Code:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            state.usbBluetoothPin,
                            style = MaterialTheme.typography.displayMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Open Android Bluetooth settings and pair with your RNode using this PIN.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Waiting for PIN from RNode...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { viewModel.exitUsbBluetoothPairingMode() }) {
                        Text("Exit Pairing Mode")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // USB device list
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(
                items = state.usbDevices,
                key = { it.deviceId },
            ) { device ->
                UsbDeviceCard(
                    device = device,
                    isSelected = state.selectedUsbDevice?.deviceId == device.deviceId,
                    onSelect = { viewModel.selectUsbDevice(device) },
                    isRequestingPermission = state.isRequestingUsbPermission,
                )
            }

            // Bluetooth pairing mode option (when device is selected)
            if (state.selectedUsbDevice != null && !state.isUsbPairingMode) {
                item {
                    Spacer(Modifier.height(8.dp))
                    OutlinedCard(
                        onClick = { viewModel.enterUsbBluetoothPairingMode() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Bluetooth,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(
                                    "Enter Bluetooth Pairing Mode",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "Pair RNode with your phone via Bluetooth",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // Rescan button
            if (!state.isUsbScanning) {
                item {
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { viewModel.scanUsbDevices() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Rescan USB Devices")
                    }
                }
            }

            // Bottom spacing
            item {
                Spacer(modifier = Modifier.height(100.dp))
            }
        }
    }
}

@Composable
private fun UsbDeviceCard(
    device: DiscoveredUsbDevice,
    isSelected: Boolean,
    onSelect: () -> Unit,
    isRequestingPermission: Boolean,
) {
    Card(
        onClick = onSelect,
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier
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
                    Icons.Default.Usb,
                    contentDescription = null,
                    tint =
                        if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        device.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color =
                            if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            device.driverType,
                            style = MaterialTheme.typography.labelSmall,
                            color =
                                if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                        Text(
                            device.vidPid,
                            style = MaterialTheme.typography.labelSmall,
                            color =
                                if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                        if (device.hasPermission) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint =
                                        if (isSelected) {
                                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                )
                                Spacer(Modifier.width(2.dp))
                                Text(
                                    "Permitted",
                                    style = MaterialTheme.typography.labelSmall,
                                    color =
                                        if (isSelected) {
                                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                )
                            }
                        }
                    }
                }
            }

            // Selection indicator or permission button
            when {
                isSelected -> {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                isRequestingPermission -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                }
                !device.hasPermission -> {
                    TextButton(onClick = onSelect) {
                        Text("Grant")
                    }
                }
            }
        }
    }
}

@Composable
private fun UsbAssistedPairingCard(
    devices: List<DiscoveredRNode>,
    pin: String?,
    status: String?,
    isPairing: Boolean,
    onDeviceSelected: (DiscoveredRNode) -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Usb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "USB-Assisted Pairing",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            Spacer(Modifier.height(8.dp))

            // Status message
            status?.let {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (devices.isEmpty() && !isPairing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            // Show PIN if received
            if (pin != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "PIN: ",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                    Text(
                        pin,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            // Device selection list (when multiple RNodes found)
            if (devices.isNotEmpty() && !isPairing) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Select your RNode:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(8.dp))

                devices.forEach { device ->
                    OutlinedCard(
                        onClick = { onDeviceSelected(device) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        colors =
                            CardDefaults.outlinedCardColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Bluetooth,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    device.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                device.rssi?.let { rssi ->
                                    Text(
                                        "Signal: ${rssi}dBm",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Pairing in progress indicator
            if (isPairing) {
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Pairing...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onCancel,
                    enabled = !isPairing,
                ) {
                    Text(
                        "Cancel",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}
