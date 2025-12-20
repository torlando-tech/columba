package com.lxmf.messenger.ui.screens.settings.cards

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.service.SharingSession
import com.lxmf.messenger.ui.model.SharingDuration

/**
 * Settings card for managing location sharing preferences and active sessions.
 *
 * Features:
 * - Master toggle to enable/disable location sharing
 * - List of active sharing sessions with stop buttons
 * - Stop all sharing button
 * - Default duration picker
 * - Location precision picker
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocationSharingCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    activeSessions: List<SharingSession>,
    onStopSharing: (String) -> Unit,
    onStopAllSharing: () -> Unit,
    defaultDuration: String,
    onDefaultDurationChange: (String) -> Unit,
    locationPrecision: String,
    onLocationPrecisionChange: (String) -> Unit,
) {
    var showDurationPicker by remember { mutableStateOf(false) }
    var showPrecisionPicker by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header with toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = "Location Sharing",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Location Sharing",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                )
            }

            // Description
            Text(
                text = "Share your real-time location with contacts. " +
                    "When disabled, all active sharing sessions will be stopped.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Active sessions section (only shown when enabled and there are sessions)
            if (enabled && activeSessions.isNotEmpty()) {
                ActiveSessionsSection(
                    sessions = activeSessions,
                    onStopSharing = onStopSharing,
                    onStopAllSharing = onStopAllSharing,
                )
            }

            HorizontalDivider()

            // Default duration picker
            SettingsRow(
                label = "Default duration",
                value = getDurationDisplayText(defaultDuration),
                onClick = { showDurationPicker = true },
            )

            // Location precision picker
            SettingsRow(
                label = "Location precision",
                value = getPrecisionDisplayText(locationPrecision),
                onClick = { showPrecisionPicker = true },
            )
        }
    }

    // Duration picker dialog
    if (showDurationPicker) {
        DurationPickerDialog(
            currentDuration = defaultDuration,
            onDurationSelected = {
                onDefaultDurationChange(it)
                showDurationPicker = false
            },
            onDismiss = { showDurationPicker = false },
        )
    }

    // Precision picker dialog
    if (showPrecisionPicker) {
        PrecisionPickerDialog(
            currentPrecision = locationPrecision,
            onPrecisionSelected = {
                onLocationPrecisionChange(it)
                showPrecisionPicker = false
            },
            onDismiss = { showPrecisionPicker = false },
        )
    }
}

@Composable
private fun ActiveSessionsSection(
    sessions: List<SharingSession>,
    onStopSharing: (String) -> Unit,
    onStopAllSharing: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Currently sharing with:",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        sessions.forEach { session ->
            ActiveSessionRow(
                session = session,
                onStopSharing = { onStopSharing(session.destinationHash) },
            )
        }

        if (sessions.size > 1) {
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedButton(
                onClick = onStopAllSharing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Stop All Sharing")
            }
        }
    }
}

@Composable
private fun ActiveSessionRow(
    session: SharingSession,
    onStopSharing: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = session.displayName,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = formatTimeRemaining(session.endTime),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onStopSharing) {
            Text("Stop")
        }
    }
}

@Composable
private fun SettingsRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = "Select",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DurationPickerDialog(
    currentDuration: String,
    onDurationSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Default Duration") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Select the default duration for new location sharing sessions:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SharingDuration.entries.forEach { duration ->
                        FilterChip(
                            selected = currentDuration == duration.name,
                            onClick = { onDurationSelected(duration.name) },
                            label = { Text(duration.displayText) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
    )
}

@Composable
private fun PrecisionPickerDialog(
    currentPrecision: String,
    onPrecisionSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Location Precision") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Choose the accuracy of your shared location:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))

                PrecisionOption(
                    title = "Precise",
                    description = "Full GPS accuracy for exact location",
                    isSelected = currentPrecision == "PRECISE",
                    onClick = { onPrecisionSelected("PRECISE") },
                )

                PrecisionOption(
                    title = "Approximate",
                    description = "Reduced accuracy (~100m radius)",
                    isSelected = currentPrecision == "APPROXIMATE",
                    onClick = { onPrecisionSelected("APPROXIMATE") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
    )
}

@Composable
private fun PrecisionOption(
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

/**
 * Format time remaining until sharing session ends.
 */
private fun formatTimeRemaining(endTime: Long?): String {
    if (endTime == null) return "Until stopped"
    val remaining = endTime - System.currentTimeMillis()
    if (remaining <= 0) return "Expiring..."

    val minutes = remaining / 60_000
    val hours = minutes / 60

    return when {
        hours > 0 -> "${hours}h ${minutes % 60}m remaining"
        else -> "${minutes}m remaining"
    }
}

/**
 * Get display text for a SharingDuration enum name.
 */
private fun getDurationDisplayText(durationName: String): String {
    return try {
        SharingDuration.valueOf(durationName).displayText
    } catch (e: IllegalArgumentException) {
        "1 hour" // Default fallback
    }
}

/**
 * Get display text for a precision setting.
 */
private fun getPrecisionDisplayText(precision: String): String {
    return when (precision) {
        "PRECISE" -> "Precise"
        "APPROXIMATE" -> "Approximate"
        else -> "Precise"
    }
}
