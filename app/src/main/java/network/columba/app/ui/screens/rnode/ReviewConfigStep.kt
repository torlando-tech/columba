package network.columba.app.ui.screens.rnode

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import network.columba.app.R
import network.columba.app.data.model.FrequencySlotCalculator
import network.columba.app.ui.components.IfacConfigCard
import network.columba.app.ui.components.InterfaceModeSelector
import network.columba.app.ui.components.NetworkRestrictionSelector
import network.columba.app.viewmodel.RNodeWizardViewModel

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
                            isTcpMode -> stringResource(R.string.rnode_wizard_review_connection)
                            isUsbMode -> stringResource(R.string.rnode_wizard_review_usb_device)
                            else -> stringResource(R.string.rnode_wizard_review_device)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                    Text(
                        viewModel.getEffectiveDeviceName().resolve(LocalContext.current),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        viewModel.getConnectionTypeString().resolve(LocalContext.current),
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
                label = { Text(stringResource(R.string.rnode_wizard_interface_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = state.nameError != null,
                supportingText = state.nameError?.let { { Text(it.resolve(LocalContext.current)) } },
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
                                stringResource(R.string.rnode_wizard_review_region_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                region.name,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                stringResource(
                                    R.string.rnode_wizard_review_region_specs,
                                    region.frequency / 1_000_000.0,
                                    region.maxTxPower,
                                    region.dutyCycleDisplay,
                                ),
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
                                    stringResource(R.string.rnode_wizard_duty_cycle_limit, region.dutyCycle),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Text(
                                    if (region.dutyCycle <= 1) {
                                        stringResource(R.string.rnode_wizard_duty_strict, region.dutyCycle)
                                    } else {
                                        stringResource(R.string.rnode_wizard_duty_limited, region.dutyCycle)
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
                            if (preset.name.startsWith("SHORT")) {
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
                                stringResource(R.string.rnode_wizard_review_modem_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(preset.displayNameRes),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "SF${preset.spreadingFactor} • ${preset.bandwidth / 1000} kHz • 4/${preset.codingRate}",
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
                                stringResource(R.string.rnode_wizard_review_slot_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(R.string.rnode_wizard_slot_value, state.selectedSlot),
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
                            stringResource(R.string.rnode_wizard_review_preset_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "${preset.countryName} - ${preset.cityOrRegion ?: stringResource(R.string.rnode_wizard_default)}",
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
            Text(stringResource(R.string.rnode_wizard_advanced_settings))
        }

        // Region limits for validation hints
        val regionLimits = viewModel.getRegionLimits()

        AnimatedVisibility(visible = state.showAdvancedSettings) {
            Column {
                Spacer(Modifier.height(16.dp))

                // Radio settings header
                Text(
                    stringResource(R.string.rnode_wizard_radio_settings),
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
                        label = { Text(stringResource(R.string.rnode_wizard_frequency_hz)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = state.frequencyError != null,
                        supportingText = {
                            Text(
                                state.frequencyError?.resolve(LocalContext.current)
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
                        label = { Text(stringResource(R.string.rnode_wizard_bandwidth_hz)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = state.bandwidthError != null,
                        supportingText = state.bandwidthError?.let { { Text(it.resolve(LocalContext.current)) } },
                    )
                }

                Spacer(Modifier.height(8.dp))

                // SF, CR, TX Power row
                val maxTxPower = regionLimits?.maxTxPower ?: 27

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = state.spreadingFactor,
                        onValueChange = { viewModel.updateSpreadingFactor(it) },
                        label = { Text("SF") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = state.spreadingFactorError != null,
                        supportingText = state.spreadingFactorError?.let { { Text(it.resolve(LocalContext.current)) } },
                        placeholder = { Text("7-12") },
                    )
                    OutlinedTextField(
                        value = state.codingRate,
                        onValueChange = { viewModel.updateCodingRate(it) },
                        label = { Text("CR") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = state.codingRateError != null,
                        supportingText = state.codingRateError?.let { { Text(it.resolve(LocalContext.current)) } },
                        placeholder = { Text("5-8") },
                    )
                    OutlinedTextField(
                        value = state.txPower,
                        onValueChange = { viewModel.updateTxPower(it) },
                        label = { Text("TX (dBm)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = state.txPowerError != null,
                        supportingText = {
                            Text(
                                state.txPowerError?.resolve(LocalContext.current) ?: stringResource(R.string.rnode_wizard_max_dbm, maxTxPower),
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
                        stringResource(R.string.rnode_wizard_airtime_limits),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))

                    val maxAirtime = regionLimits?.dutyCycle?.takeIf { it < 100 }
                    val airtimePlaceholder = maxAirtime?.let { stringResource(R.string.rnode_wizard_max_pct, it) } ?: stringResource(R.string.rnode_wizard_optional)

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = state.stAlock,
                            onValueChange = { viewModel.updateStAlock(it) },
                            label = { Text(stringResource(R.string.rnode_wizard_short_term)) },
                            placeholder = { Text(airtimePlaceholder) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            isError = state.stAlockError != null,
                            supportingText = state.stAlockError?.let { { Text(it.resolve(LocalContext.current)) } },
                        )
                        OutlinedTextField(
                            value = state.ltAlock,
                            onValueChange = { viewModel.updateLtAlock(it) },
                            label = { Text(stringResource(R.string.rnode_wizard_long_term)) },
                            placeholder = { Text(airtimePlaceholder) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            isError = state.ltAlockError != null,
                            supportingText = state.ltAlockError?.let { { Text(it.resolve(LocalContext.current)) } },
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    Text(
                        if (maxAirtime != null) {
                            stringResource(R.string.rnode_wizard_duty_limit_note, maxAirtime)
                        } else {
                            stringResource(R.string.rnode_wizard_airtime_hint)
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

                    // Network transport restriction (Wi-Fi / Cellular / Any). Only meaningful
                    // when the RNode is reached over TCP/Wi-Fi — Bluetooth and USB connections
                    // are out-of-band, so the selector is rendered but disabled with a hint.
                    Column {
                        NetworkRestrictionSelector(
                            selectedRestriction = state.networkRestriction,
                            onRestrictionChange = { viewModel.updateNetworkRestriction(it) },
                        )
                        if (!isTcpMode) {
                            Text(
                                stringResource(R.string.rnode_wizard_tcp_only_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // IFAC (network_name + passphrase). Shared card matches the
                    // TCP Client wizard and interface-config dialog for a
                    // consistent "enter network access credentials" UI.
                    IfacConfigCard(
                        networkName = state.networkName,
                        passphrase = state.passphrase,
                        passphraseVisible = state.passphraseVisible,
                        onNetworkNameChange = { viewModel.updateNetworkName(it) },
                        onPassphraseChange = { viewModel.updatePassphrase(it) },
                        onPassphraseVisibilityToggle = { viewModel.togglePassphraseVisible() },
                        description =
                            stringResource(R.string.rnode_wizard_ifac_desc),
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
                                stringResource(R.string.rnode_wizard_display_logo),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                stringResource(R.string.rnode_wizard_display_logo_desc),
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
