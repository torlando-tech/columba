package com.lxmf.messenger.ui.screens.rnode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.data.model.FrequencyRegion
import com.lxmf.messenger.data.model.RNodeRegionalPreset
import com.lxmf.messenger.ui.components.CustomSettingsCard
import com.lxmf.messenger.viewmodel.RNodeWizardViewModel

/**
 * Step 2: Region/Frequency Selection
 *
 * Shows frequency band regions (US/EU/AU/Asia) for selecting the operating frequency,
 * with a collapsible section for popular local presets at the bottom.
 */
@Composable
fun RegionSelectionStep(viewModel: RNodeWizardViewModel) {
    val state by viewModel.state.collectAsState()

    // For popular presets section
    val filteredCountries =
        remember(state.searchQuery) {
            viewModel.getFilteredCountries()
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
    ) {
        // Header
        Text(
            text = "Select Frequency Region",
            style = MaterialTheme.typography.headlineSmall,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Choose your region to set the correct frequency band and power limits.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Frequency Region Cards
            items(
                items = viewModel.getFrequencyRegions(),
                key = { it.id },
            ) { region ->
                FrequencyRegionCard(
                    region = region,
                    isSelected = state.selectedFrequencyRegion?.id == region.id,
                    onClick = { viewModel.selectFrequencyRegion(region) },
                )
            }

            // Custom option
            item {
                CustomSettingsCard(
                    title = "Custom Settings",
                    description = "Configure all parameters manually",
                    isSelected = state.isCustomMode,
                    onClick = { viewModel.enableCustomMode() },
                )
            }

            // Divider before popular presets
            item {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
            }

            // Collapsible Popular Presets Section
            item {
                PopularPresetsHeader(
                    expanded = state.showPopularPresets,
                    onToggle = { viewModel.togglePopularPresets() },
                )
            }

            // Popular presets content (when expanded)
            if (state.showPopularPresets) {
                // Show country selection or preset selection
                if (state.selectedCountry == null) {
                    // Search field
                    item {
                        OutlinedTextField(
                            value = state.searchQuery,
                            onValueChange = { viewModel.updateSearchQuery(it) },
                            label = { Text("Search countries") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    // Country list
                    items(
                        items = filteredCountries,
                        key = { "country_$it" },
                    ) { country ->
                        CountryCard(
                            countryName = country,
                            onClick = { viewModel.selectCountry(country) },
                        )
                    }
                } else {
                    // Back button and selected country header
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { viewModel.selectCountry(null) }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back to countries",
                                )
                            }
                            Icon(
                                Icons.Default.Public,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                state.selectedCountry ?: "",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    // Presets for selected country
                    items(
                        items = viewModel.getPresetsForSelectedCountry(),
                        key = { it.id },
                    ) { preset ->
                        PopularPresetCard(
                            preset = preset,
                            isSelected = state.selectedPreset?.id == preset.id,
                            onSelect = { viewModel.selectPreset(preset) },
                        )
                    }
                }
            }

            // Bottom spacing for navigation bar
            item {
                Spacer(modifier = Modifier.height(100.dp))
            }
        }
    }
}

@Composable
private fun FrequencyRegionCard(
    region: FrequencyRegion,
    isSelected: Boolean,
    onClick: () -> Unit,
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
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.SignalCellularAlt,
                contentDescription = null,
                tint =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = region.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = region.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingChip(
                        label = "${region.frequency / 1_000_000.0} MHz",
                        isSelected = isSelected,
                    )
                    SettingChip(
                        label = "${region.maxTxPower} dBm",
                        isSelected = isSelected,
                    )
                    if (region.hasDutyCycleLimit) {
                        SettingChip(
                            label = "${region.dutyCycle}% duty",
                            isSelected = isSelected,
                        )
                    }
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
private fun PopularPresetsHeader(
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    TextButton(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Popular Local Presets",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CountryCard(
    countryName: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
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
            ) {
                Icon(
                    Icons.Default.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    countryName,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PopularPresetCard(
    preset: RNodeRegionalPreset,
    isSelected: Boolean,
    onSelect: () -> Unit,
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
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    preset.cityOrRegion ?: "Default",
                    style = MaterialTheme.typography.titleMedium,
                    color =
                        if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                )
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Text(
                preset.description,
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )

            Spacer(Modifier.height(8.dp))

            // Settings preview - show all parameters since these are complete presets
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SettingChip(
                    label = "${preset.frequency / 1_000_000.0} MHz",
                    isSelected = isSelected,
                )
                SettingChip(
                    label = "SF${preset.spreadingFactor}",
                    isSelected = isSelected,
                )
                SettingChip(
                    label = "${preset.bandwidth / 1000} kHz",
                    isSelected = isSelected,
                )
                SettingChip(
                    label = "${preset.txPower} dBm",
                    isSelected = isSelected,
                )
            }
        }
    }
}

@Composable
private fun SettingChip(
    label: String,
    isSelected: Boolean,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color =
            if (isSelected) {
                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
    )
}
