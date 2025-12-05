package com.lxmf.messenger.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.util.validation.ValidationConstants
import com.lxmf.messenger.viewmodel.InterfaceConfigState

/**
 * Dialog for adding or editing a Reticulum network interface configuration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterfaceConfigDialog(
    configState: InterfaceConfigState,
    isEditing: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onConfigUpdate: (InterfaceConfigState) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (isEditing) "Edit Interface" else "Add Interface")
        },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Interface Name
                OutlinedTextField(
                    value = configState.name,
                    onValueChange = { newValue ->
                        // VALIDATION: Enforce interface name length limit
                        if (newValue.length <= ValidationConstants.MAX_INTERFACE_NAME_LENGTH) {
                            onConfigUpdate(configState.copy(name = newValue))
                        }
                    },
                    label = { Text("Interface Name") },
                    placeholder = { Text("e.g., Home WiFi, Laptop TCP") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = configState.nameError != null,
                    supportingText = {
                        val nameError = configState.nameError
                        if (nameError != null) {
                            Text(nameError)
                        } else {
                            Text("${configState.name.length}/${ValidationConstants.MAX_INTERFACE_NAME_LENGTH}")
                        }
                    },
                )

                // Interface Type Selector
                InterfaceTypeSelector(
                    selectedType = configState.type,
                    enabled = !isEditing, // Can't change type when editing
                    onTypeChange = { onConfigUpdate(configState.copy(type = it)) },
                )

                // TCP Client Target Host (required field, shown by default)
                if (configState.type == "TCPClient") {
                    OutlinedTextField(
                        value = configState.targetHost,
                        onValueChange = { host ->
                            // VALIDATION: Trim whitespace from hostname/IP
                            onConfigUpdate(configState.copy(targetHost = host.trim()))
                        },
                        label = { Text("Target Host *") },
                        placeholder = { Text("IP address or hostname") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = configState.targetHostError != null,
                        supportingText = configState.targetHostError?.let { { Text(it) } },
                    )
                }

                // Enabled Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Enabled",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Switch(
                        checked = configState.enabled,
                        onCheckedChange = { onConfigUpdate(configState.copy(enabled = it)) },
                    )
                }

                // Advanced Options (Expandable)
                var showAdvanced by remember { mutableStateOf(false) }

                Divider()

                OutlinedButton(
                    onClick = { showAdvanced = !showAdvanced },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = if (showAdvanced) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Advanced Options")
                }

                AnimatedVisibility(visible = showAdvanced) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Type-specific configuration
                        when (configState.type) {
                            "AutoInterface" -> AutoInterfaceFields(configState, onConfigUpdate)
                            "TCPClient" -> TCPClientFields(configState, onConfigUpdate)
                            "AndroidBLE" -> AndroidBLEFields(configState, onConfigUpdate)
                        }

                        Divider()

                        // Interface Mode
                        InterfaceModeSelector(
                            selectedMode = configState.mode,
                            onModeChange = { onConfigUpdate(configState.copy(mode = it)) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onSave) {
                Text(if (isEditing) "Update" else "Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterfaceTypeSelector(
    selectedType: String,
    enabled: Boolean,
    onTypeChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    val types =
        listOf(
            "AutoInterface" to "Auto Discovery",
            "TCPClient" to "TCP Client",
            "AndroidBLE" to "Bluetooth LE",
        )

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = types.find { it.first == selectedType }?.second ?: "Unknown",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Interface Type") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            types.forEach { (type, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onTypeChange(type)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
fun AutoInterfaceFields(
    configState: InterfaceConfigState,
    onConfigUpdate: (InterfaceConfigState) -> Unit,
) {
    Text(
        "Auto Discovery Configuration",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )

    OutlinedTextField(
        value = configState.groupId,
        onValueChange = { onConfigUpdate(configState.copy(groupId = it)) },
        label = { Text("Group ID (optional)") },
        placeholder = { Text("Leave empty for default network") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )

    DiscoveryScopeSelector(
        selectedScope = configState.discoveryScope,
        onScopeChange = { onConfigUpdate(configState.copy(discoveryScope = it)) },
    )

    OutlinedTextField(
        value = configState.discoveryPort,
        onValueChange = { onConfigUpdate(configState.copy(discoveryPort = it)) },
        label = { Text("Discovery Port") },
        placeholder = { Text("29716 (default)") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = configState.discoveryPortError != null,
        supportingText = configState.discoveryPortError?.let { { Text(it) } },
    )

    OutlinedTextField(
        value = configState.dataPort,
        onValueChange = { onConfigUpdate(configState.copy(dataPort = it)) },
        label = { Text("Data Port") },
        placeholder = { Text("42671 (default)") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = configState.dataPortError != null,
        supportingText = configState.dataPortError?.let { { Text(it) } },
    )
}

@Composable
fun TCPClientFields(
    configState: InterfaceConfigState,
    onConfigUpdate: (InterfaceConfigState) -> Unit,
) {
    Text(
        "TCP Client Configuration",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )

    OutlinedTextField(
        value = configState.targetPort,
        onValueChange = { onConfigUpdate(configState.copy(targetPort = it)) },
        label = { Text("Target Port") },
        placeholder = { Text("4242") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = configState.targetPortError != null,
        supportingText = configState.targetPortError?.let { { Text(it) } },
    )

    // Network Name (IFAC)
    OutlinedTextField(
        value = configState.networkName,
        onValueChange = { onConfigUpdate(configState.copy(networkName = it)) },
        label = { Text("Network Name") },
        placeholder = { Text("Optional") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        supportingText = {
            Text(
                "Optional: Sets the virtual network name for this segment. " + 
                    "This allows multiple separate networks to exist on the same " +
                    "physical channel or medium.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )

    // Passphrase (IFAC)
    OutlinedTextField(
        value = configState.passphrase,
        onValueChange = { onConfigUpdate(configState.copy(passphrase = it)) },
        label = { Text("Passphrase") },
        placeholder = { Text("Optional") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation =
            if (configState.passphraseVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
        trailingIcon = {
            IconButton(
                onClick = {
                    onConfigUpdate(configState.copy(passphraseVisible = !configState.passphraseVisible))
                },
            ) {
                Icon(
                    imageVector =
                        if (configState.passphraseVisible) {
                            Icons.Default.VisibilityOff
                        } else {
                            Icons.Default.Visibility
                        },
                    contentDescription =
                        if (configState.passphraseVisible) {
                            "Hide passphrase"
                        } else {
                            "Show passphrase"
                        },
                )
            }
        },
        supportingText = {
            Text(
                "Optional: Sets an authentication passphrase on the interface. " +
                    "This can be used in conjunction with Network Name, or alone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScopeSelector(
    selectedScope: String,
    onScopeChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    val scopes =
        listOf(
            "link" to "Link (local network only)",
            "admin" to "Admin",
            "site" to "Site",
            "organisation" to "Organisation",
            "global" to "Global",
        )

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = scopes.find { it.first == selectedScope }?.second ?: "Link",
            onValueChange = {},
            readOnly = true,
            label = { Text("Discovery Scope") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            scopes.forEach { (scope, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onScopeChange(scope)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterfaceModeSelector(
    selectedMode: String,
    onModeChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    val modes =
        listOf(
            "full" to "Full (all features enabled)",
            "gateway" to "Gateway (path discovery for others)",
            "access_point" to "Access Point (quiet unless active)",
            "roaming" to "Roaming (mobile relative to others)",
            "boundary" to "Boundary",
        )

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = modes.find { it.first == selectedMode }?.second ?: "Roaming (mobile relative to others)",
            onValueChange = {},
            readOnly = true,
            label = { Text("Interface Mode") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            modes.forEach { (mode, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onModeChange(mode)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
fun AndroidBLEFields(
    configState: InterfaceConfigState,
    onConfigUpdate: (InterfaceConfigState) -> Unit,
) {
    Text(
        "Bluetooth LE Configuration",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )

    OutlinedTextField(
        value = configState.deviceName,
        onValueChange = { newValue ->
            // VALIDATION: Enforce device name length limit
            if (newValue.length <= ValidationConstants.MAX_DEVICE_NAME_LENGTH) {
                onConfigUpdate(configState.copy(deviceName = newValue))
            }
        },
        label = { Text("Device Name (optional)") },
        placeholder = { Text("Leave empty to omit from advertisement") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = configState.deviceNameError != null,
        supportingText = {
            Column {
                configState.deviceNameError?.let { Text(it) }
                if (configState.deviceName.isNotEmpty()) {
                    Text(
                        "${configState.deviceName.length}/${ValidationConstants.MAX_DEVICE_NAME_LENGTH} characters",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "Optional: For debugging only. Keep short (max 8 chars recommended) or leave empty to maximize BLE advertisement reliability.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )

    OutlinedTextField(
        value = configState.maxConnections,
        onValueChange = { onConfigUpdate(configState.copy(maxConnections = it)) },
        label = { Text("Max Connections") },
        placeholder = { Text("7") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = configState.maxConnectionsError != null,
        supportingText = {
            Column {
                configState.maxConnectionsError?.let { Text(it) }
                Text(
                    "Maximum simultaneous BLE peers (recommended: 7)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

