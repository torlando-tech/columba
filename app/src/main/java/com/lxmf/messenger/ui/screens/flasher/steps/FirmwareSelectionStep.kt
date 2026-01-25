package com.lxmf.messenger.ui.screens.flasher.steps

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.reticulum.flasher.FirmwarePackage
import com.lxmf.messenger.reticulum.flasher.FrequencyBand
import com.lxmf.messenger.reticulum.flasher.RNodeBoard

/**
 * Step 3: Firmware Selection
 *
 * Allows selection of board type, frequency band, and firmware version.
 * Shows cached firmware and option to download new versions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirmwareSelectionStep(
    selectedBoard: RNodeBoard?,
    selectedBand: FrequencyBand,
    availableFirmware: List<FirmwarePackage>,
    selectedFirmware: FirmwarePackage?,
    availableVersions: List<String>,
    selectedVersion: String?,
    isDownloading: Boolean,
    downloadProgress: Int,
    downloadError: String?,
    useManualSelection: Boolean,
    onBoardSelected: (RNodeBoard) -> Unit,
    onBandSelected: (FrequencyBand) -> Unit,
    onFirmwareSelected: (FirmwarePackage) -> Unit,
    onDownloadFirmware: (String) -> Unit,
    onProvisionOnly: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "Firmware Selection",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Choose the firmware to flash",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Board selection (only if manual selection enabled)
        if (useManualSelection) {
            BoardSelectionCard(
                selectedBoard = selectedBoard,
                onBoardSelected = onBoardSelected,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Frequency band selection
        FrequencyBandCard(
            selectedBand = selectedBand,
            onBandSelected = onBandSelected,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Download error
        if (downloadError != null) {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
            ) {
                Text(
                    text = downloadError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        // Download progress
        if (isDownloading) {
            DownloadProgressCard(progress = downloadProgress)
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Version selection / cached firmware
        FirmwareVersionCard(
            selectedBoard = selectedBoard,
            availableFirmware = availableFirmware,
            selectedFirmware = selectedFirmware,
            availableVersions = availableVersions,
            selectedVersion = selectedVersion,
            isDownloading = isDownloading,
            onFirmwareSelected = onFirmwareSelected,
            onDownloadFirmware = onDownloadFirmware,
        )

        // Provision only option (skip flashing)
        if (selectedBoard != null) {
            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Already Flashed?",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "If you've already flashed the firmware externally, you can skip to provisioning the EEPROM.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    androidx.compose.material3.OutlinedButton(
                        onClick = onProvisionOnly,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Provision Only (Skip Flashing)")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoardSelectionCard(
    selectedBoard: RNodeBoard?,
    onBoardSelected: (RNodeBoard) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    // Filter to flashable boards
    val boards =
        RNodeBoard.entries.filter {
            it != RNodeBoard.UNKNOWN && it.platform != com.lxmf.messenger.reticulum.flasher.RNodePlatform.AVR
        }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Board Type",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                OutlinedTextField(
                    value = selectedBoard?.displayName ?: "Select board",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    boards.forEach { board ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(board.displayName)
                                    Text(
                                        text = board.platform.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                onBoardSelected(board)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FrequencyBandCard(
    selectedBand: FrequencyBand,
    onBandSelected: (FrequencyBand) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.RadioButtonChecked,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Frequency Band",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selectedBand == FrequencyBand.BAND_868_915,
                    onClick = { onBandSelected(FrequencyBand.BAND_868_915) },
                    label = { Text("868/915 MHz") },
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                )
                FilterChip(
                    selected = selectedBand == FrequencyBand.BAND_433,
                    onClick = { onBandSelected(FrequencyBand.BAND_433) },
                    label = { Text("433 MHz") },
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                )
            }

            Text(
                text = "Select the frequency band that matches your regional regulations",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun DownloadProgressCard(
    progress: Int,
    modifier: Modifier = Modifier,
) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Downloading firmware...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "$progress%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FirmwareVersionCard(
    selectedBoard: RNodeBoard?,
    availableFirmware: List<FirmwarePackage>,
    selectedFirmware: FirmwarePackage?,
    availableVersions: List<String>,
    selectedVersion: String?,
    isDownloading: Boolean,
    onFirmwareSelected: (FirmwarePackage) -> Unit,
    onDownloadFirmware: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Firmware Version",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            if (selectedBoard == null) {
                Text(
                    text = "Select a board type first",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (availableFirmware.isEmpty() && availableVersions.isEmpty()) {
                Text(
                    text = "No firmware available. Connect to the internet to download.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Show cached firmware
                if (availableFirmware.isNotEmpty()) {
                    Text(
                        text = "Cached firmware:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    availableFirmware.forEach { firmware ->
                        FilterChip(
                            selected = selectedFirmware == firmware,
                            onClick = { onFirmwareSelected(firmware) },
                            label = {
                                Text("v${firmware.version}")
                            },
                            colors =
                                FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                ),
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Show available versions for download
                if (availableVersions.isNotEmpty()) {
                    Text(
                        text = "Available for download:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                    ) {
                        OutlinedTextField(
                            value = selectedVersion ?: "Select version",
                            onValueChange = {},
                            readOnly = true,
                            enabled = !isDownloading,
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                            },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )

                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                        ) {
                            availableVersions.forEach { version ->
                                DropdownMenuItem(
                                    text = { Text("v$version") },
                                    onClick = {
                                        onDownloadFirmware(version)
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // Show selected firmware info
            selectedFirmware?.let { firmware ->
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Selected: ${firmware.board.displayName} v${firmware.version}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "Band: ${firmware.frequencyBand.displayName} | Platform: ${firmware.platform.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
