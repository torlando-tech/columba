package com.lxmf.messenger.ui.screens.rnode

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.R
import com.lxmf.messenger.data.model.FrequencySlotCalculator
import com.lxmf.messenger.viewmodel.RNodeWizardViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewConfigStep(viewModel: RNodeWizardViewModel) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .imePadding()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
    ) {
        // Device summary
        val isTcpMode = viewModel.isTcpMode()
        val isUsbMode = viewModel.isUsbMode()
        Card(
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    when {
                        isTcpMode -> Icons.Default.Wifi
                        isUsbMode -> Icons.Default.Usb
                        else -> Icons.Default.Bluetooth
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        when {
                            isTcpMode -> stringResource(R.string.rnode_connection)
                            isUsbMode -> stringResource(R.string.rnode_usb_device)
                            else -> stringResource(R.string.rnode_device)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                    Text(
                        viewModel.getEffectiveDeviceName(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        viewModel.getConnectionTypeString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Interface name (not relevant for transport mode)
        if (!state.transportMode) {
            OutlinedTextField(
                value = state.interfaceName,
                onValueChange = { viewModel.updateInterfaceName(it) },
                label = { Text(stringResource(R.string.rnode_interface_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = state.nameError != null,
                supportingText = state.nameError?.let { { Text(it) } },
            )

            Spacer(Modifier.height(16.dp))
        }

        // In custom mode or when using a popular preset, skip showing region/modem/slot summary cards
        // since user is either configuring manually or using preset values
        if (!state.isCustomMode && state.selectedPreset == null) {
            // Frequency region summary
            state.selectedFrequencyRegion?.let { region ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.SignalCellularAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                stringResource(R.string.rnode_frequency_region),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                region.name,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "${region.frequency / 1_000_000.0} MHz • ${region.maxTxPower} dBm max • ${region.dutyCycleDisplay}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                // Duty cycle warning for restricted regions
                if (region.hasDutyCycleLimit) {
                    Card(
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    stringResource(R.string.rnode_duty_cycle_limit, region.dutyCycle),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Text(
                                    if (region.dutyCycle <= 1) {
                                        stringResource(R.string.rnode_duty_cycle_strict_description, region.dutyCycle)
                                    } else {
                                        stringResource(R.string.rnode_duty_cycle_description, region.dutyCycle)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            // Modem preset summary
            state.selectedModemPreset?.let { preset ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (preset.displayName.startsWith("Short")) {
                                Icons.Default.Speed
                            } else {
                                Icons.Default.Radio
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                stringResource(R.string.rnode_wizard_title_select_modem_preset),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                preset.displayName,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                stringResource(
                                    R.string.rnode_modem_preset_summary,
                                    preset.spreadingFactor,
                                    preset.bandwidth / 1000,
                                    preset.codingRate,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // Frequency slot summary
            state.selectedFrequencyRegion?.let { region ->
                val frequency = viewModel.getFrequencyForSlot(state.selectedSlot)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Radio,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                stringResource(R.string.rnode_wizard_title_select_frequency_slot),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(R.string.rnode_slot_number_format, state.selectedSlot),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                FrequencySlotCalculator.formatFrequency(frequency),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        } // end if (!state.isCustomMode)

        // Popular preset summary (if using city-specific preset)
        state.selectedPreset?.let { preset ->
            Card(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Public,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            stringResource(R.string.rnode_popular_preset),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(
                                R.string.rnode_preset_location,
                                preset.countryName,
                                preset.cityOrRegion ?: stringResource(R.string.rnode_default_label),
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(16.dp))

        // Advanced settings (expandable)
        OutlinedButton(
            onClick = { viewModel.toggleAdvancedSettings() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                if (state.showAdvancedSettings) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.rnode_advanced_settings))
        }

        // Region limits for validation hints
        val regionLimits = viewModel.getRegionLimits()

        AnimatedVisibility(visible = state.showAdvancedSettings) {
            Column {
                Spacer(Modifier.height(16.dp))

                // Radio settings header
                Text(
                    stringResource(R.string.rnode_radio_settings),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                // Frequency and Bandwidth row
                val minFreqMhz = regionLimits?.let { it.minFrequency / 1_000_000.0 }
                val maxFreqMhz = regionLimits?.let { it.maxFrequency / 1_000_000.0 }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = state.frequency,
                        onValueChange = { viewModel.updateFrequency(it) },
                        label = { Text(stringResource(R.string.rnode_frequency_hz)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = state.frequencyError != null,
                        supportingText = {
                            Text(
                                state.frequencyError
                                    ?: if (minFreqMhz != null && maxFreqMhz != null) {
                                        "%.1f-%.1f MHz".format(minFreqMhz, maxFreqMhz)
                                    } else {
                                        ""
                                    },
                                color =
                                    if (state.frequencyError != null) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                        },
                    )
                    OutlinedTextField(
                        value = state.bandwidth,
                        onValueChange = { viewModel.updateBandwidth(it) },
                        label = { Text(stringResource(R.string.rnode_bandwidth_hz)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = state.bandwidthError != null,
                        supportingText = state.bandwidthError?.let { { Text(it) } },
                    )
                }

                Spacer(Modifier.height(8.dp))

                // SF, CR, TX Power row
                val maxTxPower = regionLimits?.maxTxPower ?: 22

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = state.spreadingFactor,
                        onValueChange = { viewModel.updateSpreadingFactor(it) },
                        label = { Text(stringResource(R.string.rnode_sf)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = state.spreadingFactorError != null,
                        supportingText = state.spreadingFactorError?.let { { Text(it) } },
                        placeholder = { Text("7-12") },
                    )
                    OutlinedTextField(
                        value = state.codingRate,
                        onValueChange = { viewModel.updateCodingRate(it) },
                        label = { Text(stringResource(R.string.rnode_cr)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = state.codingRateError != null,
                        supportingText = state.codingRateError?.let { { Text(it) } },
                        placeholder = { Text("5-8") },
                    )
                    OutlinedTextField(
                        value = state.txPower,
                        onValueChange = { viewModel.updateTxPower(it) },
                        label = { Text(stringResource(R.string.rnode_tx_dbm)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = state.txPowerError != null,
                        supportingText = {
                            Text(
                                state.txPowerError ?: stringResource(R.string.rnode_max_tx_power, maxTxPower),
                                color =
                                    if (state.txPowerError != null) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                        },
                        placeholder = { Text("0-$maxTxPower") },
                    )
                }

                // Airtime limits, interface mode, and framebuffer are not relevant for transport mode
                if (!state.transportMode) {
                    Spacer(Modifier.height(16.dp))

                    // Airtime limits
                    Text(
                        stringResource(R.string.rnode_airtime_limits),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))

                    val maxAirtime = regionLimits?.dutyCycle?.takeIf { it < 100 }
                    val airtimePlaceholder = maxAirtime?.let { stringResource(R.string.rnode_max_percent, it) } ?: stringResource(R.string.rnode_optional)

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = state.stAlock,
                            onValueChange = { viewModel.updateStAlock(it) },
                            label = { Text(stringResource(R.string.rnode_short_term_percent)) },
                            placeholder = { Text(airtimePlaceholder) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            isError = state.stAlockError != null,
                            supportingText = state.stAlockError?.let { { Text(it) } },
                        )
                        OutlinedTextField(
                            value = state.ltAlock,
                            onValueChange = { viewModel.updateLtAlock(it) },
                            label = { Text(stringResource(R.string.rnode_long_term_percent)) },
                            placeholder = { Text(airtimePlaceholder) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            isError = state.ltAlockError != null,
                            supportingText = state.ltAlockError?.let { { Text(it) } },
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    Text(
                        if (maxAirtime != null) {
                            stringResource(R.string.rnode_regional_duty_cycle_limit, maxAirtime)
                        } else {
                            stringResource(R.string.rnode_duty_cycle_help)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (maxAirtime != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )

                    Spacer(Modifier.height(16.dp))

                    // Interface mode selector
                    InterfaceModeSelector(
                        selectedMode = state.interfaceMode,
                        onModeChange = { viewModel.updateInterfaceMode(it) },
                    )

                    Spacer(Modifier.height(16.dp))

                    // Display logo on RNode toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.rnode_display_logo),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                stringResource(R.string.rnode_display_logo_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Switch(
                            checked = state.enableFramebuffer,
                            onCheckedChange = { viewModel.updateEnableFramebuffer(it) },
                        )
                    }
                }
            }
        }

        // Bottom spacing for navigation bar
        Spacer(Modifier.height(100.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InterfaceModeSelector(
    selectedMode: String,
    onModeChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    val modes =
        listOf(
            "full" to stringResource(R.string.rnode_interface_mode_full),
            "gateway" to stringResource(R.string.rnode_interface_mode_gateway),
            "access_point" to stringResource(R.string.rnode_interface_mode_access_point),
            "roaming" to stringResource(R.string.rnode_interface_mode_roaming),
            "boundary" to stringResource(R.string.rnode_interface_mode_boundary),
        )

    val selectedLabel = modes.find { it.first == selectedMode }?.second ?: stringResource(R.string.rnode_interface_mode_full_short)

    Column {
        Text(
            stringResource(R.string.rnode_interface_mode),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = selectedLabel,
                onValueChange = { },
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
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
            when (selectedMode) {
                "full" -> stringResource(R.string.rnode_interface_mode_full_description)
                "gateway" -> stringResource(R.string.rnode_interface_mode_gateway_description)
                "access_point" -> stringResource(R.string.rnode_interface_mode_access_point_description)
                "roaming" -> stringResource(R.string.rnode_interface_mode_roaming_description)
                "boundary" -> stringResource(R.string.rnode_interface_mode_boundary_description)
                else -> ""
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
