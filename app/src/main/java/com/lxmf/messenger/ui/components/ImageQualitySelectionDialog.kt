package com.lxmf.messenger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.data.model.ImageCompressionPreset
import com.lxmf.messenger.service.LinkSpeedProbe
import java.util.Locale

/**
 * Dialog for selecting image quality/compression level before sending.
 *
 * Shows all available presets with:
 * - Recommended preset highlighted based on link speed probe
 * - Estimated transfer times for each option
 * - Path information (hops, via relay, etc.)
 *
 * @param recommendedPreset The preset recommended based on network speed
 * @param probeState The current probe state with speed measurements
 * @param transferTimeEstimates Map of preset to estimated transfer time string
 * @param onSelect Called when user selects a preset
 * @param onDismiss Called when dialog is dismissed
 */
@Composable
fun ImageQualitySelectionDialog(
    recommendedPreset: ImageCompressionPreset,
    probeState: LinkSpeedProbe.ProbeState?,
    transferTimeEstimates: Map<ImageCompressionPreset, String?>,
    onSelect: (ImageCompressionPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedPreset by remember { mutableStateOf(recommendedPreset) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Choose Image Quality",
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Path info section
                PathInfoSection(probeState)

                Spacer(modifier = Modifier.height(16.dp))

                // Quality options
                Column(
                    modifier = Modifier.selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Show presets in order: LOW, MEDIUM, HIGH, ORIGINAL (skip AUTO)
                    listOf(
                        ImageCompressionPreset.LOW,
                        ImageCompressionPreset.MEDIUM,
                        ImageCompressionPreset.HIGH,
                        ImageCompressionPreset.ORIGINAL,
                    ).forEach { preset ->
                        QualityOption(
                            preset = preset,
                            isSelected = selectedPreset == preset,
                            isRecommended = preset == recommendedPreset,
                            transferTime = transferTimeEstimates[preset],
                            onClick = { selectedPreset = preset },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSelect(selectedPreset) },
            ) {
                Text("Send")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun PathInfoSection(probeState: LinkSpeedProbe.ProbeState?) {
    val pathInfo =
        when (probeState) {
            is LinkSpeedProbe.ProbeState.Complete -> {
                buildString {
                    probeState.result.hops?.let { append("$it hops") }

                    probeState.result.bestRateBps?.let { rate ->
                        if (isNotEmpty()) append(" • ")
                        append(formatBitrate(rate))
                    }

                    if (probeState.targetType == LinkSpeedProbe.TargetType.PROPAGATION_NODE) {
                        if (isNotEmpty()) append(" ")
                        append("(via relay)")
                    }
                }
            }
            is LinkSpeedProbe.ProbeState.Probing -> "Measuring network speed..."
            is LinkSpeedProbe.ProbeState.Failed -> "Network speed unknown"
            else -> null
        }

    if (pathInfo != null) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                text = pathInfo,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QualityOption(
    preset: ImageCompressionPreset,
    isSelected: Boolean,
    isRecommended: Boolean,
    transferTime: String?,
    onClick: () -> Unit,
) {
    val backgroundColor =
        when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            isRecommended -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f)
            else -> Color.Transparent
        }

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(
                    selected = isSelected,
                    onClick = onClick,
                    role = Role.RadioButton,
                ),
        color = backgroundColor,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = isSelected,
                // Handled by selectable modifier
                onClick = null,
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = preset.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isRecommended) FontWeight.SemiBold else FontWeight.Normal,
                    )

                    if (isRecommended) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = MaterialTheme.shapes.extraSmall,
                        ) {
                            Text(
                                text = "Recommended",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                softWrap = false,
                            )
                        }
                    }
                }

                Text(
                    text = preset.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // File size and transfer time estimate
            val sizeAndTime = buildString {
                append(formatFileSize(preset.targetSizeBytes))
                transferTime?.let { time ->
                    append(" • ")
                    append(time)
                }
            }
            Text(
                text = sizeAndTime,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Format bitrate in bits per second to human-readable string.
 */
private fun formatBitrate(bps: Long): String {
    return when {
        bps >= 1_000_000 -> String.format(Locale.US, "%.1f Mbps", bps / 1_000_000.0)
        bps >= 1_000 -> String.format(Locale.US, "%.1f kbps", bps / 1_000.0)
        else -> "$bps bps"
    }
}

/**
 * Format file size in bytes to human-readable string.
 */
private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1_000_000 -> String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format(Locale.US, "%.0f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }
}
