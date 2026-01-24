@file:Suppress("TooManyFunctions", "SwallowedException")

package com.lxmf.messenger.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lxmf.messenger.data.database.entity.InterfaceEntity
import com.lxmf.messenger.reticulum.ble.util.BlePermissionManager
import com.lxmf.messenger.ui.components.BlePermissionBottomSheet
import com.lxmf.messenger.ui.components.InterfaceConfigDialog
import com.lxmf.messenger.viewmodel.InterfaceManagementViewModel
import kotlinx.coroutines.delay

/**
 * Screen for managing Reticulum network interfaces.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterfaceManagementScreen(
    onNavigateBack: () -> Unit,
    onNavigateToRNodeWizard: (interfaceId: Long?) -> Unit = {},
    onNavigateToTcpClientWizard: () -> Unit = {},
    onNavigateToInterfaceStats: (interfaceId: Long) -> Unit = {},
    onNavigateToDiscoveredInterfaces: () -> Unit = {},
    viewModel: InterfaceManagementViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val configState by viewModel.configState.collectAsState()

    // State for delete confirmation dialog
    var interfaceToDelete by remember { mutableStateOf<InterfaceEntity?>(null) }

    // State for error dialog
    var errorDialogInterface by remember { mutableStateOf<InterfaceEntity?>(null) }

    // State for interface type selection
    var showTypeSelector by remember { mutableStateOf(false) }

    // Permission state
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            // After permissions granted, dismiss the request
            viewModel.dismissBlePermissionRequest()
            // Update permission state
            val hasPermissions = BlePermissionManager.hasAllPermissions(context)
            viewModel.updateBlePermissions(hasPermissions)
        }

    // Check BLE permissions on composition and when returning to screen
    LaunchedEffect(key1 = Unit, key2 = state.showBlePermissionRequest) {
        val hasPermissions = BlePermissionManager.hasAllPermissions(context)
        viewModel.updateBlePermissions(hasPermissions)
    }

    // Show permission bottom sheet when needed
    LaunchedEffect(state.showBlePermissionRequest) {
        if (state.showBlePermissionRequest) {
            // Bottom sheet will be shown below
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network Interfaces") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Show Apply Changes button when there are pending changes
                    if (state.hasPendingChanges) {
                        Button(
                            onClick = { viewModel.applyChanges() },
                            enabled = !state.isApplyingChanges,
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiary,
                                ),
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Apply Changes")
                        }
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showTypeSelector = true },
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Interface")
            }
        },
    ) { paddingValues ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // Summary Card
                    Card(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
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
                            horizontalArrangement = Arrangement.SpaceAround,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "${state.enabledCount}",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    "Enabled",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "${state.totalCount}",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    "Total",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            // Show Discovered count when discovery is enabled and has interfaces
                            if (state.isDiscoveryEnabled && state.discoveredInterfaceCount > 0) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        "${state.discoveredInterfaceCount}",
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        "Discovered",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }

                    // Discovered Interfaces Card - always show to allow navigation to discovery settings
                    DiscoveredInterfacesSummaryCard(
                        totalCount = state.discoveredInterfaceCount,
                        availableCount = state.discoveredAvailableCount,
                        unknownCount = state.discoveredUnknownCount,
                        staleCount = state.discoveredStaleCount,
                        isDiscoveryEnabled = state.isDiscoveryEnabled,
                        onClick = onNavigateToDiscoveredInterfaces,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Interfaces List
                    if (state.interfaces.isEmpty()) {
                        EmptyInterfacesView()
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.interfaces) { iface ->
                                val isOnline = state.interfaceOnlineStatus[iface.name]
                                InterfaceCard(
                                    interfaceEntity = iface,
                                    onClick = { onNavigateToInterfaceStats(iface.id) },
                                    onToggle = { enabled ->
                                        val hasPermissions = BlePermissionManager.hasAllPermissions(context)
                                        viewModel.toggleInterface(iface.id, enabled, hasPermissions)
                                    },
                                    onEdit = {
                                        // Use wizard for RNode, dialog for other types
                                        if (iface.type == "RNode") {
                                            onNavigateToRNodeWizard(iface.id)
                                        } else {
                                            viewModel.showEditDialog(iface)
                                        }
                                    },
                                    onDelete = { interfaceToDelete = iface },
                                    bluetoothState = state.bluetoothState,
                                    blePermissionsGranted = state.blePermissionsGranted,
                                    isOnline = isOnline,
                                    onErrorClick = {
                                        errorDialogInterface = iface
                                    },
                                    onReconnect =
                                        if (iface.type == "RNode") {
                                            { viewModel.reconnectRNodeInterface() }
                                        } else {
                                            null
                                        },
                                )
                            }
                        }
                    }
                }
            }

            // Success/Error Messages
            AnimatedVisibility(
                visible = state.successMessage != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
            ) {
                state.successMessage?.let { message ->
                    SuccessMessage(message) {
                        viewModel.clearSuccess()
                    }
                }
            }

            AnimatedVisibility(
                visible = state.errorMessage != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
            ) {
                state.errorMessage?.let { message ->
                    ErrorMessage(message) {
                        viewModel.clearError()
                    }
                }
            }

            AnimatedVisibility(
                visible = state.infoMessage != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
            ) {
                state.infoMessage?.let { message ->
                    InfoMessage(message) {
                        viewModel.clearInfo()
                    }
                }
            }
        }
    }

    // Add/Edit Dialog
    if (state.showAddDialog) {
        InterfaceConfigDialog(
            configState = configState,
            isEditing = state.editingInterface != null,
            onDismiss = { viewModel.hideDialog() },
            onSave = { viewModel.saveInterface() },
            onConfigUpdate = { newState -> viewModel.updateConfigState { newState } },
        )
    }

    // Delete Confirmation Dialog
    interfaceToDelete?.let { iface ->
        DeleteConfirmationDialog(
            interfaceName = iface.name,
            onConfirm = {
                viewModel.deleteInterface(iface.id)
                interfaceToDelete = null
            },
            onDismiss = { interfaceToDelete = null },
        )
    }

    // Interface Error Dialog
    errorDialogInterface?.let { iface ->
        val isOnline = state.interfaceOnlineStatus[iface.name]
        val errorMessage =
            iface.getErrorMessage(
                state.bluetoothState,
                state.blePermissionsGranted,
                isOnline,
            )
        if (errorMessage != null) {
            InterfaceErrorDialog(
                interfaceName = iface.name,
                errorMessage = errorMessage,
                onDismiss = { errorDialogInterface = null },
            )
        }
    }

    // Apply Changes Blocking Dialog
    if (state.isApplyingChanges) {
        ApplyChangesDialog()
    }

    // Apply Error Dialog
    state.applyChangesError?.let { error ->
        ApplyErrorDialog(
            errorMessage = error,
            onDismiss = { viewModel.clearApplyError() },
        )
    }

    // Bluetooth permission bottom sheet
    if (state.showBlePermissionRequest) {
        BlePermissionBottomSheet(
            onDismiss = { viewModel.dismissBlePermissionRequest() },
            onRequestPermissions = {
                viewModel.dismissBlePermissionRequest()
                val permissions = BlePermissionManager.getRequiredPermissions()
                permissionLauncher.launch(permissions.toTypedArray())
            },
            sheetState = sheetState,
        )
    }

    // Interface type selection dialog
    if (showTypeSelector) {
        InterfaceTypeSelector(
            onTypeSelected = { type ->
                showTypeSelector = false
                when (type) {
                    "RNode" -> onNavigateToRNodeWizard(null)
                    "TCPClient" -> onNavigateToTcpClientWizard()
                    "TCPServer" -> {
                        viewModel.showAddDialog()
                        viewModel.updateConfigState {
                            it.copy(type = type, name = "TCP Server", mode = "full")
                        }
                    }
                    else -> {
                        viewModel.showAddDialog()
                        viewModel.updateConfigState { it.copy(type = type) }
                    }
                }
            },
            onDismiss = { showTypeSelector = false },
        )
    }
}

@Composable
fun InterfaceCard(
    interfaceEntity: InterfaceEntity,
    onClick: (() -> Unit)? = null,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    bluetoothState: Int,
    blePermissionsGranted: Boolean,
    isOnline: Boolean? = null,
    onErrorClick: (() -> Unit)? = null,
    onReconnect: (() -> Unit)? = null,
) {
    // Determine if toggle should be enabled and if there's an error
    val toggleEnabled = interfaceEntity.shouldToggleBeEnabled(bluetoothState, blePermissionsGranted)
    val errorMessage = interfaceEntity.getErrorMessage(bluetoothState, blePermissionsGranted, isOnline)

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        Modifier.clickable(onClick = onClick)
                    } else {
                        Modifier
                    },
                ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = interfaceEntity.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )

                    // For TCPServer, make the description tappable to copy address
                    if (interfaceEntity.type == "TCPServer") {
                        val clipboardManager = LocalClipboardManager.current
                        val context = LocalContext.current
                        val (localIp, isYggdrasil) = getLocalIpAddress()
                        val copyAddress =
                            try {
                                val json = org.json.JSONObject(interfaceEntity.configJson)
                                val port = json.optInt("listen_port", 4242)
                                if (localIp != null) formatAddressWithPort(localIp, port, isYggdrasil) else null
                            } catch (e: Exception) {
                                null
                            }

                        Text(
                            text = getInterfaceDescription(interfaceEntity),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier =
                                Modifier.clickable(enabled = copyAddress != null) {
                                    copyAddress?.let {
                                        clipboardManager.setText(AnnotatedString(it))
                                        Toast.makeText(context, "Copied $it", Toast.LENGTH_SHORT).show()
                                    }
                                },
                        )
                    } else {
                        Text(
                            text = getInterfaceDescription(interfaceEntity),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Switch(
                    checked = interfaceEntity.enabled,
                    onCheckedChange = onToggle,
                    enabled = toggleEnabled,
                )
            }

            // Status and Error Badges
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Status Badge
                Surface(
                    color =
                        if (interfaceEntity.enabled) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        text = if (interfaceEntity.enabled) "ENABLED" else "DISABLED",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            if (interfaceEntity.enabled) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }

                // Error Badge (only show if interface is enabled and there's an error)
                if (interfaceEntity.enabled && errorMessage != null) {
                    if (onErrorClick != null) {
                        // Clickable error badge
                        Surface(
                            onClick = onErrorClick,
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = "Tap for details",
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Text(
                                    text = errorMessage,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    } else {
                        // Non-clickable error badge
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Text(
                                    text = errorMessage,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }

                // Reconnect button for offline RNode interfaces
                val showReconnect =
                    interfaceEntity.type == "RNode" &&
                        interfaceEntity.enabled &&
                        isOnline == false &&
                        onReconnect != null
                if (showReconnect) {
                    TextButton(
                        onClick = onReconnect,
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary,
                            ),
                    ) {
                        Text("Reconnect", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Edit")
                }

                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                    colors =
                        ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
fun EmptyInterfacesView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "No interfaces configured",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Tap + to add your first network interface",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun DeleteConfirmationDialog(
    interfaceName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text("Delete Interface?") },
        text = {
            Text("Are you sure you want to delete \"$interfaceName\"? This action cannot be undone.")
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun SuccessMessage(
    message: String,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) {
        delay(3000)
        onDismiss()
    }

    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
fun ErrorMessage(
    message: String,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) {
        delay(5000)
        onDismiss()
    }

    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
fun InfoMessage(
    message: String,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) {
        delay(3000)
        onDismiss()
    }

    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/**
 * Blocking dialog shown while applying configuration changes.
 */
@Composable
fun ApplyChangesDialog() {
    AlertDialog(
        onDismissRequest = { /* Cannot dismiss - blocking */ },
        icon = {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
            )
        },
        title = { Text("Applying Changes") },
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

/**
 * Dialog shown when configuration apply fails.
 */
@Composable
fun ApplyErrorDialog(
    errorMessage: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text("Failed to Apply Changes") },
        text = {
            Column {
                Text("An error occurred while applying configuration changes:")
                Spacer(Modifier.height(8.dp))
                Text(
                    errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your changes have been saved to the database. Try applying them again, or restart the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("OK")
            }
        },
    )
}

/**
 * Get user-friendly label for interface type.
 */
internal fun getInterfaceTypeLabel(type: String): String {
    return when (type) {
        "AutoInterface" -> "Auto Discovery"
        "TCPClient" -> "TCP Client"
        "TCPServer" -> "TCP Server"
        "RNode" -> "RNode LoRa"
        "UDP" -> "UDP Interface"
        "AndroidBLE" -> "Bluetooth LE"
        else -> type
    }
}

// TODO: Nice-to-have: Show connected peer count for TCP Server interfaces.
//       TCPServerInterface.clients returns len(spawned_interfaces).
//       Implementation requires: Python → IPC → ViewModel → UI layers.

/**
 * Format an IP address with port, using brackets for IPv6.
 */
internal fun formatAddressWithPort(
    ip: String?,
    port: Int,
    isIpv6: Boolean,
): String {
    return when {
        ip == null -> "no network:$port"
        isIpv6 || ip.contains(":") -> "[$ip]:$port"
        else -> "$ip:$port"
    }
}

/**
 * Get the device's local IP address, preferring Yggdrasil if available.
 * Returns a pair of (ipAddress, isYggdrasil).
 */
@Suppress("NestedBlockDepth", "SwallowedException")
private fun getLocalIpAddress(): Pair<String?, Boolean> {
    var ipv4Address: String? = null
    var yggdrasilAddress: String? = null

    try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (networkInterface.isLoopback || !networkInterface.isUp) continue
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address.isLoopbackAddress) continue

                if (address is java.net.Inet6Address) {
                    // Check for Yggdrasil address (200::/7 range - first byte is 0x02 or 0x03)
                    val bytes = address.address
                    if (bytes.size >= 1 && (bytes[0] == 0x02.toByte() || bytes[0] == 0x03.toByte())) {
                        yggdrasilAddress = address.hostAddress?.split("%")?.get(0) // Remove zone ID
                    }
                } else if (address is java.net.Inet4Address && ipv4Address == null) {
                    ipv4Address = address.hostAddress
                }
            }
        }
    } catch (e: Exception) {
        // Ignore errors
    }

    // Prefer Yggdrasil if available
    return if (yggdrasilAddress != null) {
        Pair(yggdrasilAddress, true)
    } else {
        Pair(ipv4Address, false)
    }
}

/**
 * Get interface description including type and relevant details.
 */
@Composable
private fun getInterfaceDescription(interfaceEntity: InterfaceEntity): String {
    val typeLabel = getInterfaceTypeLabel(interfaceEntity.type)
    return when (interfaceEntity.type) {
        "TCPServer" -> {
            try {
                val json = org.json.JSONObject(interfaceEntity.configJson)
                val listenPort = json.optInt("listen_port", 4242)
                val (localIp, isYggdrasil) = getLocalIpAddress()
                val networkType = if (isYggdrasil) " (Yggdrasil)" else ""
                val addressDisplay = formatAddressWithPort(localIp, listenPort, isYggdrasil)
                "$typeLabel$networkType · $addressDisplay"
            } catch (e: Exception) {
                typeLabel
            }
        }
        else -> typeLabel
    }
}

/**
 * Dialog for selecting interface type when adding a new interface.
 */
@Composable
fun InterfaceTypeSelector(
    onTypeSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var advancedExpanded by remember { mutableStateOf(false) }
    val rotationAngle by animateFloatAsState(
        targetValue = if (advancedExpanded) 90f else 0f,
        animationSpec = tween(150),
        label = "rotation",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Interface Type") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InterfaceTypeOption(
                    title = "Auto Discovery",
                    description = "Automatically discover peers on local network",
                    onClick = { onTypeSelected("AutoInterface") },
                )
                InterfaceTypeOption(
                    title = "TCP Client",
                    description = "Connect to a remote Reticulum transport node",
                    onClick = { onTypeSelected("TCPClient") },
                )
                InterfaceTypeOption(
                    title = "Bluetooth LE",
                    description = "Direct connection to Columba users and Linux ble-reticulum devices",
                    onClick = { onTypeSelected("AndroidBLE") },
                )
                InterfaceTypeOption(
                    title = "RNode LoRa",
                    description = "Connects to separate RNode hardware via BLE or Bluetooth Classic",
                    onClick = { onTypeSelected("RNode") },
                )

                // Collapsible Advanced section
                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { advancedExpanded = !advancedExpanded },
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Advanced",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = if (advancedExpanded) "Collapse" else "Expand",
                            modifier = Modifier.rotate(rotationAngle),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                AnimatedVisibility(
                    visible = advancedExpanded,
                    enter = expandVertically(animationSpec = tween(150)),
                    exit = shrinkVertically(animationSpec = tween(100)),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        InterfaceTypeOption(
                            title = "TCP Server",
                            description = "Accept incoming connections from other Reticulum nodes",
                            onClick = { onTypeSelected("TCPServer") },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
internal fun InterfaceTypeOption(
    title: String,
    description: String,
    onClick: () -> Unit,
    isHighlighted: Boolean = false,
) {
    Card(
        onClick = onClick,
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isHighlighted) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color =
                    if (isHighlighted) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (isHighlighted) {
                        MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    },
            )
        }
    }
}

/**
 * Dialog to show detailed interface error information.
 */
@Composable
fun InterfaceErrorDialog(
    interfaceName: String,
    errorMessage: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text("Interface Issue") },
        text = {
            Column {
                Text(
                    text = interfaceName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("OK")
            }
        },
    )
}

/**
 * Summary card for discovered interfaces from RNS 1.1.x discovery.
 * Tappable to navigate to the discovered interfaces detail screen.
 */
@Composable
fun DiscoveredInterfacesSummaryCard(
    totalCount: Int,
    availableCount: Int,
    unknownCount: Int,
    staleCount: Int,
    isDiscoveryEnabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isDiscoveryEnabled) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint =
                            if (isDiscoveryEnabled) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                    Text(
                        text = "Interface Discovery",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color =
                            if (isDiscoveryEnabled) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                if (isDiscoveryEnabled) {
                    if (totalCount > 0) {
                        Text(
                            text = "$totalCount interfaces found via RNS Discovery",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // Status breakdown
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (availableCount > 0) {
                                StatusBadge(
                                    count = availableCount,
                                    label = "available",
                                    dotColor = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (unknownCount > 0) {
                                StatusBadge(
                                    count = unknownCount,
                                    label = "unknown",
                                    dotColor = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                            if (staleCount > 0) {
                                StatusBadge(
                                    count = staleCount,
                                    label = "stale",
                                    dotColor = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "Discovery enabled - no interfaces found yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                        )
                    }
                } else {
                    Text(
                        text = "Tap to configure RNS 1.1.x interface discovery",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "View details",
                tint =
                    if (isDiscoveryEnabled) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
    }
}

/**
 * Small status badge showing count with colored dot.
 */
@Composable
private fun StatusBadge(
    count: Int,
    label: String,
    dotColor: androidx.compose.ui.graphics.Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            modifier = Modifier.size(8.dp),
            shape = RoundedCornerShape(50),
            color = dotColor,
        ) {}
        Text(
            text = "$count $label",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
        )
    }
}
