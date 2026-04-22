package network.columba.app.ui.screens.flasher.steps

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import network.columba.app.data.model.FrequencyRegion
import network.columba.app.data.model.FrequencyRegions
import network.columba.app.data.model.ModemPreset

/**
 * Step 4d: TNC Configuration (microReticulum only)
 *
 * After flashing microReticulum firmware, configure the radio parameters
 * for standalone transport mode via USB serial commands.
 *
 * Uses region selection and modem presets (same as RNode wizard) to avoid
 * manual parameter entry, with an advanced section for overrides.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TncConfigurationStep(
    frequencyMhz: String,
    bandwidthKhz: String,
    spreadingFactor: String,
    codingRate: String,
    txPower: String,
    isConfiguring: Boolean,
    configError: String?,
    isStandaloneConfig: Boolean = false,
    selectedRegion: FrequencyRegion? = null,
    selectedPreset: ModemPreset = ModemPreset.DEFAULT,
    onRegionSelected: (FrequencyRegion) -> Unit = {},
    onPresetSelected: (ModemPreset) -> Unit = {},
    onFrequencyChanged: (String) -> Unit,
    onBandwidthChanged: (String) -> Unit,
    onSpreadingFactorChanged: (String) -> Unit,
    onCodingRateChanged: (String) -> Unit,
    onTxPowerChanged: (String) -> Unit,
    onApply: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAdvanced by remember { mutableStateOf(false) }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isStandaloneConfig) Icons.Default.Settings else Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = if (isStandaloneConfig) "Transport Configuration" else "Flash Successful",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Configure transport mode",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text =
                    "microReticulum operates as a standalone transport node. " +
                        "Select your region and modem preset below. " +
                        "These settings are saved to the device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(12.dp),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Region selection
        Text(
            text = "Region",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Common regions as chips
        val commonRegions =
            remember {
                FrequencyRegions.regions.filter {
                    it.id in listOf("us_915", "eu_868_l", "eu_433", "au_915", "jp_920", "kr_920")
                }
            }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            commonRegions.forEach { region ->
                FilterChip(
                    selected = selectedRegion?.id == region.id,
                    onClick = { onRegionSelected(region) },
                    label = { Text(region.name.substringBefore(" (")) },
                    enabled = !isConfiguring,
                )
            }
        }

        // Show all regions expandable
        var showAllRegions by remember { mutableStateOf(false) }
        if (!showAllRegions) {
            TextButton(onClick = { showAllRegions = true }) {
                Text("Show all regions")
                Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }

        AnimatedVisibility(visible = showAllRegions) {
            Column {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FrequencyRegions.regions
                        .filter { it.id !in commonRegions.map { r -> r.id } }
                        .forEach { region ->
                            FilterChip(
                                selected = selectedRegion?.id == region.id,
                                onClick = { onRegionSelected(region) },
                                label = { Text(region.name.substringBefore(" (")) },
                                enabled = !isConfiguring,
                            )
                        }
                }
                TextButton(onClick = { showAllRegions = false }) {
                    Text("Show fewer")
                    Icon(Icons.Default.ExpandLess, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }

        if (selectedRegion != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            text = "${String.format(java.util.Locale.US, "%.3f", selectedRegion.frequency / 1_000_000.0)} MHz",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "TX: ${selectedRegion.defaultTxPower} dBm (max ${selectedRegion.maxTxPower})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Modem preset selection
        Text(
            text = "Modem Preset",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )

        Spacer(modifier = Modifier.height(8.dp))

        ModemPreset.entries.forEach { preset ->
            TncPresetCard(
                preset = preset,
                isSelected = selectedPreset == preset,
                onClick = { onPresetSelected(preset) },
                enabled = !isConfiguring,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Advanced settings (expandable)
        TextButton(
            onClick = { showAdvanced = !showAdvanced },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Advanced Settings")
            Icon(
                imageVector = if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }

        AnimatedVisibility(visible = showAdvanced) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = frequencyMhz,
                        onValueChange = onFrequencyChanged,
                        label = { Text("Frequency (MHz)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        enabled = !isConfiguring,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = bandwidthKhz,
                            onValueChange = onBandwidthChanged,
                            label = { Text("BW (kHz)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            enabled = !isConfiguring,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = spreadingFactor,
                            onValueChange = onSpreadingFactorChanged,
                            label = { Text("SF (7-12)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            enabled = !isConfiguring,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = codingRate,
                            onValueChange = onCodingRateChanged,
                            label = { Text("CR (5-8)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            enabled = !isConfiguring,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = txPower,
                            onValueChange = onTxPowerChanged,
                            label = { Text("TX Power (dBm)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            enabled = !isConfiguring,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // Config error
        if (configError != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = configError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Actions
        Button(
            onClick = onApply,
            enabled = !isConfiguring && selectedRegion != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isConfiguring) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Configuring...")
            } else {
                Text("Apply Configuration")
            }
        }

        TextButton(
            onClick = onSkip,
            enabled = !isConfiguring,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isStandaloneConfig) "Cancel" else "Skip (configure later)")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TncPresetCard(
    preset: ModemPreset,
    isSelected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val containerColor =
        if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        enabled = enabled,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector =
                    when {
                        preset.name.startsWith("SHORT") -> Icons.Default.Speed
                        else -> Icons.Default.SignalCellularAlt
                    },
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = preset.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    )
                    if (preset == ModemPreset.DEFAULT) {
                        Spacer(Modifier.width(8.dp))
                        Card(
                            colors =
                                CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                ),
                        ) {
                            Text(
                                text = "Recommended",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }

                Text(
                    text = preset.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(4.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ParamChip(label = "SF${preset.spreadingFactor}")
                    ParamChip(label = "${preset.bandwidth / 1000} kHz")
                    ParamChip(label = "4/${preset.codingRate}")
                }
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ParamChip(label: String) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
