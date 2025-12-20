package com.lxmf.messenger.ui.screens.settings.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.data.model.ImageCompressionPreset

/**
 * Settings card for selecting image compression preset.
 * Shows all presets with descriptions and highlights the detected preset when AUTO is selected.
 *
 * @param selectedPreset The currently selected preset
 * @param detectedPreset The detected optimal preset (shown when AUTO is selected)
 * @param hasSlowInterface Whether any slow interface (RNode/BLE) is enabled
 * @param onPresetChange Callback when user selects a different preset
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImageCompressionCard(
    selectedPreset: ImageCompressionPreset,
    detectedPreset: ImageCompressionPreset?,
    hasSlowInterface: Boolean,
    onPresetChange: (ImageCompressionPreset) -> Unit,
) {
    android.util.Log.d("ImageCompressionCard", "Rendering: selected=$selectedPreset, detected=$detectedPreset, hasSlowInterface=$hasSlowInterface")
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = "Image Compression",
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Image Compression",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Description
            Text(
                text =
                    "Select compression level for image attachments. " +
                        "Auto mode detects your network type and selects the optimal preset.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Preset chips
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ImageCompressionPreset.entries.forEach { preset ->
                    val isSelected = selectedPreset == preset
                    val label = buildPresetLabel(preset, detectedPreset, isSelected)

                    FilterChip(
                        selected = isSelected,
                        onClick = { onPresetChange(preset) },
                        label = { Text(label) },
                        colors =
                            FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                    )
                }
            }

            // Selected preset description
            PresetDescription(
                preset = selectedPreset,
                detectedPreset = detectedPreset,
            )

            // Warning when ORIGINAL is selected with slow interfaces
            if (selectedPreset == ImageCompressionPreset.ORIGINAL && hasSlowInterface) {
                SlowInterfaceWarning()
            }
        }
    }
}

/**
 * Build the label for a preset chip.
 */
private fun buildPresetLabel(
    preset: ImageCompressionPreset,
    detectedPreset: ImageCompressionPreset?,
    isSelected: Boolean,
): String {
    return when {
        preset == ImageCompressionPreset.AUTO && isSelected && detectedPreset != null -> {
            "Auto (${detectedPreset.displayName})"
        }
        else -> preset.displayName
    }
}

/**
 * Description of the selected preset.
 */
@Composable
private fun PresetDescription(
    preset: ImageCompressionPreset,
    detectedPreset: ImageCompressionPreset?,
) {
    val displayPreset =
        if (preset == ImageCompressionPreset.AUTO) {
            detectedPreset ?: preset
        } else {
            preset
        }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = displayPreset.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (displayPreset != ImageCompressionPreset.AUTO) {
                Text(
                    text =
                        "Max: ${formatDimension(displayPreset.maxDimensionPx)}px, " +
                            "${formatBytes(displayPreset.targetSizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/**
 * Warning shown when ORIGINAL preset is selected but slow interfaces are enabled.
 */
@Composable
private fun SlowInterfaceWarning() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "Warning",
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text =
                    "Slow interfaces (LoRa/BLE) are enabled. " +
                        "Sending large images may take a very long time or fail.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/**
 * Format dimension for display.
 */
private fun formatDimension(px: Int): String {
    return if (px == Int.MAX_VALUE) "unlimited" else px.toString()
}

/**
 * Format bytes into a human-readable string.
 */
private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }
}
