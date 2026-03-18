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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.R
import com.lxmf.messenger.data.model.ImageCompressionPreset
import com.lxmf.messenger.ui.components.CollapsibleSettingsCard

/**
 * Settings card for selecting image compression preset.
 * Shows all presets with descriptions and highlights the detected preset when AUTO is selected.
 *
 * @param isExpanded Whether the card is currently expanded
 * @param onExpandedChange Callback when expansion state changes
 * @param selectedPreset The currently selected preset
 * @param detectedPreset The detected optimal preset (shown when AUTO is selected)
 * @param hasSlowInterface Whether any slow interface (RNode/BLE) is enabled
 * @param onPresetChange Callback when user selects a different preset
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImageCompressionCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    selectedPreset: ImageCompressionPreset,
    detectedPreset: ImageCompressionPreset?,
    hasSlowInterface: Boolean,
    onPresetChange: (ImageCompressionPreset) -> Unit,
) {
    android.util.Log.d("ImageCompressionCard", "Rendering: selected=$selectedPreset, detected=$detectedPreset, hasSlowInterface=$hasSlowInterface")
    CollapsibleSettingsCard(
        title = stringResource(R.string.image_compression_title),
        icon = Icons.Default.Image,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
    ) {
        // Description
        Text(
            text = stringResource(R.string.image_compression_description),
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
                val label = resolvedPresetLabel(preset, detectedPreset, isSelected)

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
    val presetDescription = localizedPresetDescription(displayPreset)
    val presetMetrics = stringResource(R.string.image_compression_metrics, formatDimension(displayPreset.maxDimensionPx), formatBytes(displayPreset.targetSizeBytes))

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
                text = presetDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (displayPreset != ImageCompressionPreset.AUTO) {
                Text(
                    text = presetMetrics,
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
                contentDescription = stringResource(R.string.image_compression_warning_content_description),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(R.string.image_compression_slow_interface_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun localizedPresetLabel(preset: ImageCompressionPreset): String =
    when (preset) {
        ImageCompressionPreset.LOW -> stringResource(R.string.image_compression_preset_low)
        ImageCompressionPreset.MEDIUM -> stringResource(R.string.image_compression_preset_medium)
        ImageCompressionPreset.HIGH -> stringResource(R.string.image_compression_preset_high)
        ImageCompressionPreset.ORIGINAL -> stringResource(R.string.image_compression_preset_original)
        ImageCompressionPreset.AUTO -> stringResource(R.string.image_compression_preset_auto)
    }

@Composable
private fun localizedPresetDescription(preset: ImageCompressionPreset): String =
    when (preset) {
        ImageCompressionPreset.LOW -> stringResource(R.string.image_compression_description_low)
        ImageCompressionPreset.MEDIUM -> stringResource(R.string.image_compression_description_medium)
        ImageCompressionPreset.HIGH -> stringResource(R.string.image_compression_description_high)
        ImageCompressionPreset.ORIGINAL -> stringResource(R.string.image_compression_description_original)
        ImageCompressionPreset.AUTO -> stringResource(R.string.image_compression_description_auto)
    }

@Composable
private fun resolvedPresetLabel(
    preset: ImageCompressionPreset,
    detectedPreset: ImageCompressionPreset?,
    isSelected: Boolean,
): String =
    when {
        preset == ImageCompressionPreset.AUTO && isSelected && detectedPreset != null -> {
            stringResource(R.string.image_compression_auto_dynamic, localizedPresetLabel(detectedPreset))
        }
        else -> localizedPresetLabel(preset)
    }

/**
 * Format dimension for display.
 */
@Composable
private fun formatDimension(px: Int): String = if (px == Int.MAX_VALUE) stringResource(R.string.image_compression_unlimited) else px.toString()

/**
 * Format bytes into a human-readable string.
 */
@Composable
private fun formatBytes(bytes: Long): String =
    when {
        bytes < 1024 -> stringResource(R.string.image_compression_bytes_b, bytes)
        bytes < 1024 * 1024 -> stringResource(R.string.image_compression_bytes_kb, bytes / 1024)
        else -> stringResource(R.string.image_compression_bytes_mb, bytes / (1024 * 1024))
    }
