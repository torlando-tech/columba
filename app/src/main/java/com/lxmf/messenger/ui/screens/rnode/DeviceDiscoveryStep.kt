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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lxmf.messenger.R
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
            stringResource(R.string.rnode_connection_method),
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
                label = { Text(stringResource(R.string.rnode_bluetooth)) },
                leadingIcon = {
                    Icon(Icons.Default.Bluetooth, contentDescription = null, Modifier.size(18.dp))
                },
            )
            FilterChip(
                selected = state.connectionType == RNodeConnectionType.TCP_WIFI,
                onClick = { viewModel.setConnectionType(RNodeConnectionType.TCP_WIFI) },
                label = { Text(stringResource(R.string.rnode_wifi_tcp)) },
                leadingIcon = {
                    Icon(Icons.Default.Wifi, contentDescription = null, Modifier.size(18.dp))
                },
            )
            FilterChip(
                selected = state.connectionType == RNodeConnectionType.USB_SERIAL,
                onClick = { viewModel.setConnectionType(RNodeConnectionType.USB_SERIAL) },
                label = { Text(stringResource(R.string.rnode_usb)) },
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
            stringResource(R.string.rnode_tcp_connection_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = tcpHost,
            onValueChange = onHostChange,
            label = { Text(stringResource(R.string.rnode_ip_address_or_hostname)) },
            placeholder = { Text(stringResource(R.string.rnode_ip_address_placeholder)) },
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
                Text(stringResource(R.string.action_test_connection))
            }

            // Validation result
            when {
                validationSuccess == true -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = stringResource(R.string.rnode_success),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.status_connected),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                validationSuccess == false -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = stringResource(R.string.status_failed),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.status_failed),
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
            stringResource(R.string.rnode_tcp_connection_help),
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
                    stringResource(R.string.rnode_scanning_for_devices),
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
                            Text(stringResource(R.string.action_dismiss))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { viewModel.retryPairing() }) {
                            Text(stringResource(R.string.action_retry))
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
                            text = stringResource(R.string.rnode_pairing),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.rnode_enter_pin_display),
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // USB-assisted pairing card - shows the same UI as the USB tab
        // This uses shared state (isUsbPairingMode, showManualPinEntry, etc.)
        if (state.isUsbPairingMode) {
            UsbBluetoothPairingCard(
                state = state,
                viewModel = viewModel,
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
                            text = stringResource(R.string.rnode_waiting_reconnect),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    state.reconnectDeviceName?.let { name ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.rnode_looking_for_device, name),
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
                            Text(stringResource(R.string.action_cancel))
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
                        Text(stringResource(R.string.action_dismiss))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Current device (in edit mode)
        if (state.isEditMode && state.selectedDevice != null && !state.showManualEntry) {
            Text(
                stringResource(R.string.rnode_current_device),
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
                stringResource(R.string.rnode_select_different_device),
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
                                stringResource(R.string.rnode_enter_device_manually),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                stringResource(R.string.rnode_device_not_listed),
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
                        stringResource(R.string.rnode_pairing_trouble),
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
                                    stringResource(R.string.rnode_pair_via_usb),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    stringResource(R.string.rnode_pair_via_usb_description),
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
                        Text(stringResource(R.string.action_scan_again))
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
                    label = { Text(stringResource(R.string.rnode_bluetooth_device_name)) },
                    placeholder = { Text(stringResource(R.string.rnode_bluetooth_device_name_placeholder)) },
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
                    stringResource(R.string.rnode_connection_type),
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
                        label = { Text(stringResource(R.string.rnode_bluetooth_classic)) },
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
                        label = { Text(stringResource(R.string.rnode_bluetooth_le)) },
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
                    stringResource(R.string.rnode_bluetooth_type_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(8.dp))

                TextButton(onClick = { viewModel.hideManualEntry() }) {
                    Text(stringResource(R.string.rnode_cancel_manual_entry))
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
                                        contentDescription = stringResource(R.string.rnode_unknown_type),
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                    Text(
                                        stringResource(R.string.rnode_unknown_type),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            } else {
                                Text(
                                    when (device.type) {
                                        BluetoothType.CLASSIC -> stringResource(R.string.rnode_classic)
                                        BluetoothType.BLE -> stringResource(R.string.rnode_ble)
                                        BluetoothType.UNKNOWN -> stringResource(R.string.rnode_unknown)
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
                                        stringResource(R.string.status_paired),
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
                            contentDescription = stringResource(R.string.status_selected),
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
                                Text(stringResource(R.string.action_pair))
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
                        stringResource(R.string.rnode_select_connection_type),
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
                            label = { Text(stringResource(R.string.rnode_bluetooth_classic)) },
                        )
                        FilterChip(
                            selected = false,
                            onClick = {
                                onSetType(BluetoothType.BLE)
                                showTypeSelector = false
                            },
                            label = { Text(stringResource(R.string.rnode_bluetooth_le)) },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.rnode_auto_detect_type_help),
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
            stringResource(R.string.rnode_usb_connection_description),
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
                    stringResource(R.string.rnode_scanning_usb_devices),
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
                        Text(stringResource(R.string.action_dismiss))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Bluetooth pairing mode card (shared with Bluetooth tab's "Pair via USB")
        if (state.isUsbPairingMode) {
            UsbBluetoothPairingCard(state = state, viewModel = viewModel)
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
                                    stringResource(R.string.rnode_enter_bluetooth_pairing_mode),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    stringResource(R.string.rnode_pair_phone_bluetooth),
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
                        Text(stringResource(R.string.action_rescan_usb_devices))
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
                                    stringResource(R.string.status_permitted),
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
                        contentDescription = stringResource(R.string.status_selected),
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
                        Text(stringResource(R.string.action_grant))
                    }
                }
            }
        }
    }
}

/**
 * Shared composable for USB Bluetooth pairing mode card.
 * Used by both the USB tab and Bluetooth tab's "Pair via USB" feature.
 */
@Composable
private fun UsbBluetoothPairingCard(
    state: RNodeWizardState,
    viewModel: RNodeWizardViewModel,
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
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.rnode_bluetooth_pairing_mode),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(8.dp))

            if (state.usbBluetoothPin != null) {
                Text(
                    stringResource(R.string.rnode_pin_code_label),
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
                // Show pairing status if auto-pairing is in progress
                if (state.usbPairingStatus != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            state.usbPairingStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.rnode_scanning_for_pairing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            } else if (state.showManualPinEntry) {
                // Manual PIN entry for devices that don't send PIN over serial
                Text(
                    stringResource(R.string.rnode_enter_six_digit_pin),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.manualPinInput,
                    onValueChange = { viewModel.updateManualPinInput(it) },
                    label = { Text(stringResource(R.string.rnode_pin_code)) },
                    placeholder = { Text("000000") },
                    singleLine = true,
                    keyboardOptions =
                        androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                        ),
                    modifier = Modifier.width(150.dp),
                    textStyle =
                        MaterialTheme.typography.headlineMedium.copy(
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            letterSpacing = 4.sp,
                        ),
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { viewModel.cancelManualPinEntry() }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    Button(
                        onClick = { viewModel.submitManualPin() },
                        enabled = state.manualPinInput.length == 6,
                    ) {
                        Text(stringResource(R.string.action_submit))
                    }
                }
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
                        state.usbPairingStatus ?: stringResource(R.string.rnode_waiting_for_pin),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(onClick = { viewModel.exitUsbBluetoothPairingMode() }) {
                Text(stringResource(R.string.rnode_exit_pairing_mode))
            }
        }
    }
}
