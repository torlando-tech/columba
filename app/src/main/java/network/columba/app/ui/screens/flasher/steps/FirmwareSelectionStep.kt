package network.columba.app.ui.screens.flasher.steps

import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import network.columba.app.R
import network.columba.app.rns.host.flasher.FirmwarePackage
import network.columba.app.rns.host.flasher.FirmwareSource
import network.columba.app.rns.host.flasher.FrequencyBand
import network.columba.app.rns.host.flasher.RNodeBoard
import network.columba.app.viewmodel.UiMessage

/**
 * Step 3: Firmware Selection
 *
 * Allows selection of board type, frequency band, and firmware version.
 * Shows cached firmware and option to download new versions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirmwareSelectionStep(
    availableFirmwareSources: List<FirmwareSource>?,
    selectedFirmwareSource: FirmwareSource,
    customFirmwareUri: Uri?,
    customFirmwareUrl: String,
    selectedBoard: RNodeBoard?,
    selectedBand: FrequencyBand,
    bandExplicitlySelected: Boolean,
    availableFirmware: List<FirmwarePackage>,
    selectedFirmware: FirmwarePackage?,
    availableVersions: List<String>,
    selectedVersion: String?,
    isDownloading: Boolean,
    downloadProgress: Int,
    downloadError: UiMessage?,
    useManualSelection: Boolean,
    onFirmwareSourceSelected: (FirmwareSource) -> Unit,
    onCustomUrlChanged: (String) -> Unit,
    onPickFile: () -> Unit,
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
            text = stringResource(R.string.flasher_title_firmware_selection),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.flasher_firmware_selection_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Firmware source selection
        FirmwareSourceCard(
            selectedSource = selectedFirmwareSource,
            availableSources = availableFirmwareSources,
            onSourceSelected = onFirmwareSourceSelected,
        )

        // microReticulum info note
        if (selectedFirmwareSource is FirmwareSource.MicroReticulum) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(R.string.flasher_about_microreticulum),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.flasher_microreticulum_info),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

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
            bandExplicitlySelected = bandExplicitlySelected,
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
                    text = downloadError.resolve(LocalContext.current),
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

        // Custom firmware input (URL or local file)
        if (selectedFirmwareSource == FirmwareSource.Custom) {
            CustomFirmwareCard(
                customFirmwareUri = customFirmwareUri,
                customFirmwareUrl = customFirmwareUrl,
                onCustomUrlChanged = onCustomUrlChanged,
                onPickFile = onPickFile,
            )
        } else {
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
        }

        // Provision only option (skip flashing)
        if (selectedBoard != null) {
            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.flasher_already_flashed),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.flasher_already_flashed_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    androidx.compose.material3.OutlinedButton(
                        onClick = onProvisionOnly,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.flasher_btn_provision_only))
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
            it != RNodeBoard.UNKNOWN && it.platform != network.columba.app.rns.host.flasher.RNodePlatform.AVR
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
                    text = stringResource(R.string.flasher_title_board_type),
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
                    value = selectedBoard?.displayName ?: stringResource(R.string.flasher_select_board),
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
    bandExplicitlySelected: Boolean,
    onBandSelected: (FrequencyBand) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (!bandExplicitlySelected) {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
            ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.RadioButtonChecked,
                    contentDescription = null,
                    tint =
                        if (!bandExplicitlySelected) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.flasher_label_frequency_band),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (!bandExplicitlySelected) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.flasher_required),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Only show as "selected" if user has explicitly confirmed
                FilterChip(
                    selected = bandExplicitlySelected && selectedBand == FrequencyBand.BAND_868_915,
                    onClick = { onBandSelected(FrequencyBand.BAND_868_915) },
                    label = { Text("868/915 MHz") },
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                )
                FilterChip(
                    selected = bandExplicitlySelected && selectedBand == FrequencyBand.BAND_433,
                    onClick = { onBandSelected(FrequencyBand.BAND_433) },
                    label = { Text("433 MHz") },
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                )
            }

            Text(
                text =
                    if (!bandExplicitlySelected) {
                        stringResource(R.string.flasher_band_confirm_warning)
                    } else {
                        stringResource(R.string.flasher_band_select_hint)
                    },
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (!bandExplicitlySelected) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
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
                    text = stringResource(R.string.flasher_downloading_firmware),
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
                    text = stringResource(R.string.flasher_title_firmware_version),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            if (selectedBoard == null) {
                Text(
                    text = stringResource(R.string.flasher_select_board_first),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (availableFirmware.isEmpty() && availableVersions.isEmpty()) {
                Text(
                    text = stringResource(R.string.flasher_no_firmware_available),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Show cached firmware
                if (availableFirmware.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.flasher_cached_firmware),
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
                        text = stringResource(R.string.flasher_available_for_download),
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
                            value = selectedVersion ?: stringResource(R.string.flasher_select_version),
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

            // Show selected firmware info OR selected version for download
            when {
                selectedFirmware != null -> {
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text =
                                    stringResource(
                                        R.string.flasher_selected_firmware_info,
                                        selectedFirmware.board.displayName,
                                        selectedFirmware.version,
                                    ),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text =
                                    stringResource(
                                        R.string.flasher_selected_firmware_band_platform,
                                        selectedFirmware.frequencyBand.displayName,
                                        selectedFirmware.platform.name,
                                    ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                selectedVersion != null && selectedBoard != null -> {
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            ),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = stringResource(R.string.flasher_will_download_version, selectedVersion),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            Text(
                                text = stringResource(R.string.flasher_will_download_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
                selectedBoard != null && availableFirmware.isNotEmpty() || availableVersions.isNotEmpty() -> {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.flasher_select_version_to_continue),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun FirmwareSourceCard(
    selectedSource: FirmwareSource,
    availableSources: List<FirmwareSource>?,
    onSourceSelected: (FirmwareSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sources =
        availableSources ?: listOf(
            FirmwareSource.Official,
            FirmwareSource.MicroReticulum,
            FirmwareSource.CommunityEdition,
            FirmwareSource.Custom,
        )

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
                    text = stringResource(R.string.flasher_title_firmware_source),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                sources.forEach { source ->
                    FilterChip(
                        selected = selectedSource == source,
                        onClick = { onSourceSelected(source) },
                        label = { Text(source.displayName) },
                        colors =
                            FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomFirmwareCard(
    customFirmwareUri: Uri?,
    customFirmwareUrl: String,
    onCustomUrlChanged: (String) -> Unit,
    onPickFile: () -> Unit,
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
                    text = stringResource(R.string.flasher_title_custom_firmware),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // URL input
            Text(
                text = stringResource(R.string.flasher_download_from_url),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = customFirmwareUrl,
                onValueChange = onCustomUrlChanged,
                label = { Text(stringResource(R.string.flasher_firmware_zip_url_label)) },
                placeholder = { Text(stringResource(R.string.flasher_firmware_url_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (customFirmwareUrl.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        ),
                ) {
                    Text(
                        text = stringResource(R.string.flasher_will_download_at_flash),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.flasher_or_pick_local_file),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))

            androidx.compose.material3.OutlinedButton(
                onClick = onPickFile,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.flasher_btn_pick_zip))
            }

            if (customFirmwareUri != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                ) {
                    Text(
                        text =
                            stringResource(
                                R.string.flasher_file_selected,
                                customFirmwareUri.lastPathSegment
                                    ?: stringResource(R.string.flasher_custom_firmware_fallback),
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
