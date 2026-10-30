package network.columba.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import network.columba.app.R
import network.columba.app.rns.api.model.InterfaceMode
import network.columba.app.rns.host.ble.model.BlePowerPreset
import network.columba.app.util.validation.ValidationConstants
import network.columba.app.viewmodel.InterfaceConfigState

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
            Text(
                if (isEditing) {
                    stringResource(R.string.iface_cfg_edit_title)
                } else {
                    stringResource(R.string.iface_cfg_add_title)
                },
            )
        },
        text = {
            val scrollState = rememberScrollState()
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState),
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
                    label = { Text(stringResource(R.string.iface_cfg_name_label)) },
                    placeholder = { Text(stringResource(R.string.iface_cfg_name_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = configState.nameError != null,
                    supportingText = {
                        val nameError = configState.nameError
                        if (nameError != null) {
                            Text(nameError.resolve(LocalContext.current))
                        } else {
                            Text("${configState.name.length}/${ValidationConstants.MAX_INTERFACE_NAME_LENGTH}")
                        }
                    },
                )

                // Interface Type Selector
                InterfaceTypeSelector(
                    selectedType = configState.type,
                    // Can't change type when editing
                    enabled = !isEditing,
                    onTypeChange = { onConfigUpdate(configState.copy(type = it)) },
                )

                // TCP Client Target Host (required field, shown by default)
                if (configState.type == "TCPClient") {
                    OutlinedTextField(
                        value = configState.targetHost,
                        onValueChange = { host ->
                            // Strip scheme prefixes and whitespace — only bare hostnames are valid
                            val cleaned =
                                host
                                    .trim()
                                    .removePrefix("http://")
                                    .removePrefix("https://")
                            onConfigUpdate(configState.copy(targetHost = cleaned))
                        },
                        label = { Text(stringResource(R.string.iface_cfg_target_host_label)) },
                        placeholder = { Text(stringResource(R.string.iface_cfg_target_host_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = configState.targetHostError != null,
                        supportingText = configState.targetHostError?.let { { Text(it.resolve(LocalContext.current)) } },
                    )
                }

                // Enabled Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.iface_cfg_enabled),
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
                    Text(stringResource(R.string.iface_cfg_advanced_options))
                }

                AnimatedVisibility(visible = showAdvanced) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Type-specific configuration
                        when (configState.type) {
                            "AutoInterface" -> AutoInterfaceFields(configState, onConfigUpdate)
                            "TCPClient" -> TCPClientFields(configState, onConfigUpdate)
                            "TCPServer" -> TCPServerFields(configState, onConfigUpdate)
                            "AndroidBLE" -> AndroidBLEFields(configState, onConfigUpdate, scrollState)
                            "RNode" -> RNodeFields(configState, onConfigUpdate)
                        }

                        Divider()

                        // Network restriction (Wi-Fi / Cellular / Any) — IP-only.
                        // Hidden for AndroidBLE (out-of-band) and for RNode unless connection
                        // mode is TCP. The filter ignores the field for non-IP interfaces, but
                        // showing the selector would be misleading.
                        if (configState.networkRestrictionApplies) {
                            NetworkRestrictionSelector(
                                selectedRestriction = configState.effectiveNetworkRestriction,
                                onRestrictionChange = {
                                    onConfigUpdate(configState.copy(networkRestriction = it))
                                },
                            )
                        }

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
                Text(if (isEditing) stringResource(R.string.iface_cfg_update) else stringResource(R.string.iface_cfg_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
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
            "AutoInterface" to R.string.iface_cfg_type_auto,
            "TCPClient" to R.string.iface_cfg_type_tcp_client,
            "TCPServer" to R.string.iface_cfg_type_tcp_server,
            "AndroidBLE" to R.string.rnode_wizard_conn_bt_le,
        )

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = stringResource(types.find { it.first == selectedType }?.second ?: R.string.label_unknown),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.iface_cfg_type_label)) },
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
                    text = { Text(stringResource(label)) },
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
        stringResource(R.string.iface_cfg_auto_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )

    OutlinedTextField(
        value = configState.groupId,
        onValueChange = { onConfigUpdate(configState.copy(groupId = it)) },
        label = { Text(stringResource(R.string.iface_cfg_group_id_label)) },
        placeholder = { Text(stringResource(R.string.iface_cfg_group_id_placeholder)) },
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
        label = { Text(stringResource(R.string.iface_cfg_discovery_port_label)) },
        placeholder = { Text(stringResource(R.string.iface_cfg_discovery_port_default)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = configState.discoveryPortError != null,
        supportingText = configState.discoveryPortError?.let { { Text(it.resolve(LocalContext.current)) } },
    )

    OutlinedTextField(
        value = configState.dataPort,
        onValueChange = { onConfigUpdate(configState.copy(dataPort = it)) },
        label = { Text(stringResource(R.string.iface_cfg_data_port_label)) },
        placeholder = { Text(stringResource(R.string.iface_cfg_data_port_default)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = configState.dataPortError != null,
        supportingText = configState.dataPortError?.let { { Text(it.resolve(LocalContext.current)) } },
    )
}

@Composable
fun TCPClientFields(
    configState: InterfaceConfigState,
    onConfigUpdate: (InterfaceConfigState) -> Unit,
) {
    Text(
        stringResource(R.string.iface_cfg_tcp_client_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )

    OutlinedTextField(
        value = configState.targetPort,
        onValueChange = { onConfigUpdate(configState.copy(targetPort = it)) },
        label = { Text(stringResource(R.string.iface_cfg_target_port_label)) },
        placeholder = { Text("4242") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = configState.targetPortError != null,
        supportingText = configState.targetPortError?.let { { Text(it.resolve(LocalContext.current)) } },
    )

    IfacConfigCard(
        networkName = configState.networkName,
        passphrase = configState.passphrase,
        passphraseVisible = configState.passphraseVisible,
        onNetworkNameChange = { onConfigUpdate(configState.copy(networkName = it)) },
        onPassphraseChange = { onConfigUpdate(configState.copy(passphrase = it)) },
        onPassphraseVisibilityToggle = {
            onConfigUpdate(configState.copy(passphraseVisible = !configState.passphraseVisible))
        },
    )

    Divider()

    // SOCKS5 Proxy Toggle
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.iface_cfg_socks_proxy_title),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.iface_cfg_socks_proxy_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = configState.socksProxyEnabled,
            onCheckedChange = { onConfigUpdate(configState.copy(socksProxyEnabled = it)) },
        )
    }

    AnimatedVisibility(visible = configState.socksProxyEnabled) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = configState.socksProxyHost,
                onValueChange = { host ->
                    onConfigUpdate(configState.copy(socksProxyHost = host.trim()))
                },
                label = { Text(stringResource(R.string.iface_cfg_proxy_host_label)) },
                placeholder = { Text("127.0.0.1") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = configState.socksProxyHostError != null,
                supportingText = {
                    val error = configState.socksProxyHostError
                    if (error != null) {
                        Text(error.resolve(LocalContext.current))
                    } else {
                        Text(
                            stringResource(R.string.iface_cfg_proxy_host_support),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )

            OutlinedTextField(
                value = configState.socksProxyPort,
                onValueChange = { onConfigUpdate(configState.copy(socksProxyPort = it)) },
                label = { Text(stringResource(R.string.iface_cfg_proxy_port_label)) },
                placeholder = { Text("9050") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = configState.socksProxyPortError != null,
                supportingText = {
                    val error = configState.socksProxyPortError
                    if (error != null) {
                        Text(error.resolve(LocalContext.current))
                    } else {
                        Text(
                            stringResource(R.string.iface_cfg_proxy_port_support),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }
    }
}

@Composable
fun RNodeFields(
    configState: InterfaceConfigState,
    onConfigUpdate: (InterfaceConfigState) -> Unit,
) {
    // RNode dialog has no per-interface settings beyond IFAC (radio config
    // lives in the full RNode wizard); the shared IFAC card already owns its
    // own header and explanation so we let it stand alone here.
    IfacConfigCard(
        networkName = configState.networkName,
        passphrase = configState.passphrase,
        passphraseVisible = configState.passphraseVisible,
        onNetworkNameChange = { onConfigUpdate(configState.copy(networkName = it)) },
        onPassphraseChange = { onConfigUpdate(configState.copy(passphrase = it)) },
        onPassphraseVisibilityToggle = {
            onConfigUpdate(configState.copy(passphraseVisible = !configState.passphraseVisible))
        },
        description =
            stringResource(R.string.iface_cfg_rnode_ifac_description),
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
            "link" to R.string.iface_cfg_scope_link,
            "admin" to R.string.iface_cfg_scope_admin,
            "site" to R.string.iface_cfg_scope_site,
            "organisation" to R.string.iface_cfg_scope_organisation,
            "global" to R.string.iface_cfg_scope_global,
        )

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = stringResource(scopes.find { it.first == selectedScope }?.second ?: R.string.iface_cfg_scope_fallback),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.iface_cfg_scope_label)) },
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
                    text = { Text(stringResource(label)) },
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

    // Options derive from the InterfaceMode enum (single source of truth);
    // display labels are a UI concern and live in strings.xml.
    val modes =
        InterfaceMode.entries.map { mode ->
            mode.value to
                stringResource(
                    when (mode) {
                        InterfaceMode.FULL -> R.string.interface_mode_full
                        InterfaceMode.GATEWAY -> R.string.interface_mode_gateway
                        InterfaceMode.ACCESS_POINT -> R.string.interface_mode_access_point
                        InterfaceMode.ROAMING -> R.string.interface_mode_roaming
                        InterfaceMode.BOUNDARY -> R.string.interface_mode_boundary
                        InterfaceMode.INTERNAL -> R.string.interface_mode_internal
                    },
                )
        }
    val knownMode = InterfaceMode.fromValue(selectedMode)
    val displayValue =
        when {
            knownMode != null -> modes.first { it.first == selectedMode }.second
            // An imported mode outside InterfaceMode is shown verbatim so the displayed
            // value always matches what will be saved, instead of silently showing "Full".
            selectedMode.isNotBlank() -> selectedMode
            else -> modes.first().second
        }

    // Per-mode explanation shown beneath the field, so users get guidance about
    // what each mode changes (restored when the RNode wizard reused this selector).
    val description =
        stringResource(
            when (knownMode) {
                InterfaceMode.FULL -> R.string.interface_mode_desc_full
                InterfaceMode.GATEWAY -> R.string.interface_mode_desc_gateway
                InterfaceMode.ACCESS_POINT -> R.string.interface_mode_desc_access_point
                InterfaceMode.ROAMING -> R.string.interface_mode_desc_roaming
                InterfaceMode.BOUNDARY -> R.string.interface_mode_desc_boundary
                InterfaceMode.INTERNAL -> R.string.interface_mode_desc_internal
                null -> R.string.interface_mode_desc_unknown
            },
        )

    Column {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = displayValue,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.interface_mode_label)) },
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

        Spacer(Modifier.height(4.dp))

        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun AndroidBLEFields(
    configState: InterfaceConfigState,
    onConfigUpdate: (InterfaceConfigState) -> Unit,
    scrollState: ScrollState? = null,
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    Text(
        stringResource(R.string.iface_cfg_ble_title),
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
        label = { Text(stringResource(R.string.iface_cfg_device_name_label)) },
        placeholder = { Text(stringResource(R.string.iface_cfg_device_name_placeholder)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = configState.deviceNameError != null,
        supportingText = {
            Column {
                configState.deviceNameError?.let { Text(it.resolve(LocalContext.current)) }
                if (configState.deviceName.isNotEmpty()) {
                    Text(
                        stringResource(
                            R.string.iface_cfg_device_name_counter,
                            configState.deviceName.length,
                            ValidationConstants.MAX_DEVICE_NAME_LENGTH,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    stringResource(R.string.iface_cfg_device_name_support),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )

    OutlinedTextField(
        value = configState.maxConnections,
        onValueChange = { onConfigUpdate(configState.copy(maxConnections = it)) },
        label = { Text(stringResource(R.string.iface_cfg_max_connections_label)) },
        placeholder = { Text("7") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = configState.maxConnectionsError != null,
        supportingText = {
            Column {
                configState.maxConnectionsError?.let { Text(it.resolve(context)) }
                Text(
                    stringResource(R.string.iface_cfg_max_connections_support),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        stringResource(R.string.iface_cfg_power_profile_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )

    // Preset selector
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        val presets = listOf("performance", "balanced", "battery_saver", "custom")
        val labels =
            listOf(
                R.string.iface_cfg_preset_performance,
                R.string.iface_cfg_preset_balanced,
                R.string.iface_cfg_preset_battery_saver,
                R.string.iface_cfg_preset_custom,
            )
        presets.forEachIndexed { index, preset ->
            SegmentedButton(
                selected = configState.blePowerPreset == preset,
                onClick = {
                    val newState =
                        if (preset != "custom") {
                            val s = BlePowerPreset.getSettings(BlePowerPreset.fromString(preset))
                            configState.copy(
                                blePowerPreset = preset,
                                bleDiscoveryIntervalMs = s.discoveryIntervalMs.toString(),
                                bleDiscoveryIntervalIdleMs = s.discoveryIntervalIdleMs.toString(),
                                bleScanDurationMs = s.scanDurationMs.toString(),
                                bleAdvertisingRefreshIntervalMs = s.advertisingRefreshIntervalMs.toString(),
                            )
                        } else {
                            configState.copy(blePowerPreset = preset)
                        }
                    onConfigUpdate(newState)
                    if (preset == "custom" && scrollState != null) {
                        coroutineScope.launch {
                            scrollState.animateScrollTo(scrollState.maxValue)
                        }
                    }
                },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = presets.size),
                icon = {},
                label = { Text(stringResource(labels[index]), maxLines = 1, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }

    // Helper text per preset
    Text(
        when (configState.blePowerPreset) {
            "performance" -> stringResource(R.string.iface_cfg_preset_desc_performance)
            "balanced" -> stringResource(R.string.iface_cfg_preset_desc_balanced)
            "battery_saver" -> stringResource(R.string.iface_cfg_preset_desc_battery_saver)
            "custom" -> stringResource(R.string.iface_cfg_preset_desc_custom)
            else -> ""
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // Custom sliders (only enabled when preset is "custom")
    val isCustom = configState.blePowerPreset == "custom"

    Text(
        stringResource(
            R.string.iface_cfg_scan_interval_active,
            configState.bleDiscoveryIntervalMs.toLongOrNull()?.let { it / 1000 } ?: 5,
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        stringResource(R.string.iface_cfg_scan_active_support),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(
        value = (configState.bleDiscoveryIntervalMs.toFloatOrNull() ?: 5000f) / 1000f,
        onValueChange = { onConfigUpdate(configState.copy(bleDiscoveryIntervalMs = (it * 1000).toLong().toString())) },
        valueRange = 3f..30f,
        steps = 26,
        enabled = isCustom,
    )

    Text(
        stringResource(
            R.string.iface_cfg_scan_interval_idle,
            configState.bleDiscoveryIntervalIdleMs.toLongOrNull()?.let { it / 1000 } ?: 30,
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        stringResource(R.string.iface_cfg_scan_idle_support),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(
        value = (configState.bleDiscoveryIntervalIdleMs.toFloatOrNull() ?: 30000f) / 1000f,
        onValueChange = { onConfigUpdate(configState.copy(bleDiscoveryIntervalIdleMs = (it * 1000).toLong().toString())) },
        valueRange = 15f..300f,
        steps = 56,
        enabled = isCustom,
    )

    Text(
        stringResource(
            R.string.iface_cfg_scan_duration,
            configState.bleScanDurationMs.toLongOrNull()?.let { it / 1000 } ?: 10,
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        stringResource(R.string.iface_cfg_scan_duration_support),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(
        value = (configState.bleScanDurationMs.toFloatOrNull() ?: 10000f) / 1000f,
        onValueChange = { onConfigUpdate(configState.copy(bleScanDurationMs = (it * 1000).toLong().toString())) },
        valueRange = 3f..15f,
        steps = 11,
        enabled = isCustom,
    )

    // Warn when scan duration is close to or exceeds the active scan interval (high duty-cycle)
    val scanDuration = configState.bleScanDurationMs.toLongOrNull() ?: 10000L
    val activeInterval = configState.bleDiscoveryIntervalMs.toLongOrNull() ?: 5000L
    if (isCustom && scanDuration >= activeInterval) {
        Text(
            stringResource(R.string.iface_cfg_scan_duty_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Text(
        stringResource(
            R.string.iface_cfg_ad_refresh_interval,
            configState.bleAdvertisingRefreshIntervalMs.toLongOrNull()?.let { it / 1000 } ?: 60,
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        stringResource(R.string.iface_cfg_ad_refresh_support),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(
        value = (configState.bleAdvertisingRefreshIntervalMs.toFloatOrNull() ?: 60000f) / 1000f,
        onValueChange = { onConfigUpdate(configState.copy(bleAdvertisingRefreshIntervalMs = (it * 1000).toLong().toString())) },
        valueRange = 30f..300f,
        steps = 26,
        enabled = isCustom,
    )
}

/**
 * Three-option segmented selector for the per-interface network restriction:
 * Any / Wi-Fi only / Cellular only. Used inside the Advanced Options section of
 * `InterfaceConfigDialog` and the wizard review steps for IP-based interface types.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkRestrictionSelector(
    selectedRestriction: String,
    onRestrictionChange: (String) -> Unit,
) {
    val restrictionSemanticsLabel = stringResource(R.string.iface_cfg_network_restriction_desc)
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = restrictionSemanticsLabel },
    ) {
        Text(
            stringResource(R.string.iface_cfg_active_on_network),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        // Values match the on-disk JSON form (NetworkRestriction.value) so the round
        // trip through configStateToInterfaceConfig is a straight string lookup.
        val options =
            listOf(
                "any" to R.string.iface_cfg_restriction_any,
                "wifi_only" to R.string.interface_restriction_wifi_only,
                "cellular_only" to R.string.interface_restriction_cellular_only,
            )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = selectedRestriction == value,
                    onClick = { onRestrictionChange(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    icon = {},
                    label = { Text(stringResource(label), maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        Text(
            stringResource(R.string.iface_cfg_restriction_support),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun TCPServerFields(
    configState: InterfaceConfigState,
    onConfigUpdate: (InterfaceConfigState) -> Unit,
) {
    Text(
        stringResource(R.string.iface_cfg_tcp_server_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )

    Text(
        stringResource(R.string.iface_cfg_tcp_server_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedTextField(
        value = configState.listenIp,
        onValueChange = { ip ->
            val cleaned =
                ip
                    .trim()
                    .removePrefix("http://")
                    .removePrefix("https://")
            onConfigUpdate(configState.copy(listenIp = cleaned))
        },
        label = { Text(stringResource(R.string.iface_cfg_listen_ip_label)) },
        placeholder = { Text("0.0.0.0") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = configState.listenIpError != null,
        supportingText = {
            Column {
                configState.listenIpError?.let { Text(it.resolve(LocalContext.current)) }
                Text(
                    stringResource(R.string.iface_cfg_listen_ip_support),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )

    OutlinedTextField(
        value = configState.listenPort,
        onValueChange = { onConfigUpdate(configState.copy(listenPort = it)) },
        label = { Text(stringResource(R.string.iface_cfg_listen_port_label)) },
        placeholder = { Text("4242") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = configState.listenPortError != null,
        supportingText = {
            Column {
                configState.listenPortError?.let { Text(it.resolve(LocalContext.current)) }
                Text(
                    stringResource(R.string.iface_cfg_listen_port_support),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )

    IfacConfigCard(
        networkName = configState.networkName,
        passphrase = configState.passphrase,
        passphraseVisible = configState.passphraseVisible,
        onNetworkNameChange = { onConfigUpdate(configState.copy(networkName = it)) },
        onPassphraseChange = { onConfigUpdate(configState.copy(passphrase = it)) },
        onPassphraseVisibilityToggle = {
            onConfigUpdate(configState.copy(passphraseVisible = !configState.passphraseVisible))
        },
        description =
            stringResource(R.string.iface_cfg_tcp_server_ifac_description),
    )
}
