package com.lxmf.messenger.ui.screens.rnode

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.data.model.RNodeRegionalPreset
import com.lxmf.messenger.viewmodel.RNodeWizardViewModel

@Composable
fun RegionSelectionStep(viewModel: RNodeWizardViewModel) {
    val state by viewModel.state.collectAsState()

    // Compute filtered countries at top level to ensure recomposition when searchQuery changes
    val filteredCountries = remember(state.searchQuery) {
        viewModel.getFilteredCountries()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        // Search field
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { viewModel.updateSearchQuery(it) },
            label = { Text("Search countries") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Spacer(Modifier.height(16.dp))

        AnimatedVisibility(visible = state.selectedCountry == null) {
            // Country list
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = filteredCountries,
                    key = { it },
                ) { country ->
                    CountryCard(
                        countryName = country,
                        onClick = { viewModel.selectCountry(country) },
                    )
                }

                // Custom option
                item {
                    OutlinedCard(
                        onClick = { viewModel.enableCustomMode() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Custom Settings",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "Configure all parameters manually",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // Bottom spacing for navigation bar
                item {
                    Spacer(modifier = Modifier.height(100.dp))
                }
            }
        }

        AnimatedVisibility(visible = state.selectedCountry != null) {
            Column {
                // Back to country list
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
                        style = MaterialTheme.typography.titleLarge,
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Preset list for selected country
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = viewModel.getPresetsForSelectedCountry(),
                        key = { it.id },
                    ) { preset ->
                        RegionPresetCard(
                            preset = preset,
                            isSelected = state.selectedPreset?.id == preset.id,
                            onSelect = { viewModel.selectPreset(preset) },
                        )
                    }

                    // Custom option within country
                    item {
                        OutlinedCard(
                            onClick = { viewModel.enableCustomMode() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Custom Settings",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        "Use different parameters",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
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

        // Custom mode selected indicator
        AnimatedVisibility(visible = state.isCustomMode) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "Custom Mode",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            "You'll configure all settings on the next screen",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                        )
                    }
                }
            }
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
            modifier = Modifier
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
private fun RegionPresetCard(
    preset: RNodeRegionalPreset,
    isSelected: Boolean,
    onSelect: () -> Unit,
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
                    color = if (isSelected) {
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
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            Spacer(Modifier.height(8.dp))

            // Settings preview
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SettingChip(
                    label = "${preset.frequency / 1_000_000.0} MHz",
                    isSelected = isSelected,
                )
                SettingChip(
                    label = "${preset.bandwidth / 1000} kHz",
                    isSelected = isSelected,
                )
                SettingChip(
                    label = "SF${preset.spreadingFactor}",
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
        color = if (isSelected) {
            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}
