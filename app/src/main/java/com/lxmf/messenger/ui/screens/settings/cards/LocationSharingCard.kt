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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.service.SharingSession
import com.lxmf.messenger.ui.components.CollapsibleSettingsCard
import com.lxmf.messenger.ui.model.SharingDuration
import com.lxmf.messenger.util.DestinationHashValidator
import kotlinx.coroutines.delay

/**
 * Settings card for managing location sharing preferences and active sessions.
 *
 * Features:
 * - Master toggle to enable/disable location sharing
 * - List of active sharing sessions with stop buttons
 * - Stop all sharing button
 * - Default duration picker
 * - Location precision picker
 * - Telemetry collector configuration
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocationSharingCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    activeSessions: List<SharingSession>,
    onStopSharing: (String) -> Unit,
    onStopAllSharing: () -> Unit,
    defaultDuration: String,
    onDefaultDurationChange: (String) -> Unit,
    locationPrecisionRadius: Int,
    onLocationPrecisionRadiusChange: (Int) -> Unit,
    // Telemetry collector props
    telemetryCollectorEnabled: Boolean,
    telemetryCollectorAddress: String?,
    telemetrySendIntervalSeconds: Int,
    lastTelemetrySendTime: Long?,
    isSendingTelemetry: Boolean,
    onTelemetryEnabledChange: (Boolean) -> Unit,
    onTelemetryCollectorAddressChange: (String?) -> Unit,
    onTelemetrySendIntervalChange: (Int) -> Unit,
    onTelemetrySendNow: () -> Unit,
    // Telemetry request props
    telemetryRequestEnabled: Boolean,
    telemetryRequestIntervalSeconds: Int,
    lastTelemetryRequestTime: Long?,
    isRequestingTelemetry: Boolean,
    onTelemetryRequestEnabledChange: (Boolean) -> Unit,
    onTelemetryRequestIntervalChange: (Int) -> Unit,
    onRequestTelemetryNow: () -> Unit,
) {
    var showDurationPicker by remember { mutableStateOf(false) }
    var showPrecisionPicker by remember { mutableStateOf(false) }

    CollapsibleSettingsCard(
        title = "Location Sharing",
        icon = Icons.Default.LocationOn,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        headerAction = {
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
            )
        },
    ) {
        // Description
        Text(
            text =
                "Share your real-time location with contacts. " +
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
            value = getPrecisionRadiusDisplayText(locationPrecisionRadius),
            onClick = { showPrecisionPicker = true },
        )

        // Telemetry Collector Section
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        TelemetryCollectorSection(
            enabled = telemetryCollectorEnabled,
            collectorAddress = telemetryCollectorAddress,
            sendIntervalSeconds = telemetrySendIntervalSeconds,
            lastSendTime = lastTelemetrySendTime,
            isSending = isSendingTelemetry,
            onEnabledChange = onTelemetryEnabledChange,
            onCollectorAddressChange = onTelemetryCollectorAddressChange,
            onSendIntervalChange = onTelemetrySendIntervalChange,
            onSendNow = onTelemetrySendNow,
            // Request props
            requestEnabled = telemetryRequestEnabled,
            requestIntervalSeconds = telemetryRequestIntervalSeconds,
            lastRequestTime = lastTelemetryRequestTime,
            isRequesting = isRequestingTelemetry,
            onRequestEnabledChange = onTelemetryRequestEnabledChange,
            onRequestIntervalChange = onTelemetryRequestIntervalChange,
            onRequestNow = onRequestTelemetryNow,
        )
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
        PrecisionRadiusPickerDialog(
            currentRadius = locationPrecisionRadius,
            onRadiusSelected = {
                onLocationPrecisionRadiusChange(it)
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
        modifier =
            Modifier
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
        modifier =
            Modifier
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
                            colors =
                                FilterChipDefaults.filterChipColors(
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

/**
 * Precision radius presets for the picker.
 */
private enum class PrecisionPreset(val radiusMeters: Int, val displayName: String, val description: String) {
    PRECISE(0, "Precise", "Exact GPS location"),
    NEIGHBORHOOD(100, "Neighborhood", "~100m radius"),
    CITY(1000, "City", "~1km radius"),
    REGION(10000, "Region", "~10km radius"),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrecisionRadiusPickerDialog(
    currentRadius: Int,
    onRadiusSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Location Precision") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Choose how precisely your location is shared:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))

                PrecisionPreset.entries.forEach { preset ->
                    PrecisionRadiusOption(
                        title = preset.displayName,
                        description = preset.description,
                        isSelected = currentRadius == preset.radiusMeters,
                        onClick = { onRadiusSelected(preset.radiusMeters) },
                    )
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
private fun PrecisionRadiusOption(
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
        colors =
            FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
    )
}

/**
 * Format time remaining until sharing session ends.
 */
internal fun formatTimeRemaining(endTime: Long?): String {
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
internal fun getDurationDisplayText(durationName: String): String {
    return try {
        SharingDuration.valueOf(durationName).displayText
    } catch (e: IllegalArgumentException) {
        "1 hour" // Default fallback
    }
}

/**
 * Get display text for a precision radius setting.
 */
internal fun getPrecisionRadiusDisplayText(radiusMeters: Int): String {
    return when (radiusMeters) {
        0 -> "Precise"
        1000 -> "Neighborhood (~1km)"
        10000 -> "City (~10km)"
        100000 -> "Region (~100km)"
        else -> if (radiusMeters >= 1000) "${radiusMeters / 1000}km" else "${radiusMeters}m"
    }
}

// =============================================================================
// Telemetry Collector Section
// =============================================================================

/**
 * Section for configuring telemetry collector integration.
 * Allows users to send location to a collector and receive locations from multiple peers.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TelemetryCollectorSection(
    enabled: Boolean,
    collectorAddress: String?,
    sendIntervalSeconds: Int,
    lastSendTime: Long?,
    isSending: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onCollectorAddressChange: (String?) -> Unit,
    onSendIntervalChange: (Int) -> Unit,
    onSendNow: () -> Unit,
    // Request props
    requestEnabled: Boolean,
    requestIntervalSeconds: Int,
    lastRequestTime: Long?,
    isRequesting: Boolean,
    onRequestEnabledChange: (Boolean) -> Unit,
    onRequestIntervalChange: (Int) -> Unit,
    onRequestNow: () -> Unit,
) {
    var addressInput by remember { mutableStateOf(collectorAddress ?: "") }

    // Sync input with external state
    LaunchedEffect(collectorAddress) {
        addressInput = collectorAddress ?: ""
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.CloudUpload,
                contentDescription = "Group Tracker",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = "Group Tracker",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        }

        // Description
        Text(
            text = "Share your location with a group and see where everyone is",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Enable toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = null,
                    indication = null,
                ) { onEnabledChange(!enabled) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Share with group",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "Automatically share your location",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = null,
            )
        }

        // Collector address input
        CollectorAddressInput(
            addressInput = addressInput,
            onAddressChange = { addressInput = it },
            onConfirm = { normalizedHash ->
                onCollectorAddressChange(normalizedHash)
            },
        )

        // Send interval chips
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Send every: ${formatTelemetryIntervalDisplay(sendIntervalSeconds)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TelemetryIntervalChip(
                    label = "5min",
                    selected = sendIntervalSeconds == 300,
                    enabled = enabled && collectorAddress != null,
                    onClick = { onSendIntervalChange(300) },
                )
                TelemetryIntervalChip(
                    label = "15min",
                    selected = sendIntervalSeconds == 900,
                    enabled = enabled && collectorAddress != null,
                    onClick = { onSendIntervalChange(900) },
                )
                TelemetryIntervalChip(
                    label = "30min",
                    selected = sendIntervalSeconds == 1800,
                    enabled = enabled && collectorAddress != null,
                    onClick = { onSendIntervalChange(1800) },
                )
                TelemetryIntervalChip(
                    label = "1hr",
                    selected = sendIntervalSeconds == 3600,
                    enabled = enabled && collectorAddress != null,
                    onClick = { onSendIntervalChange(3600) },
                )
            }
        }

        // Send Now button with last send time
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onSendNow,
                enabled = !isSending && collectorAddress != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                ),
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sending...")
                } else {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Send Now")
                }
            }
            // Last send timestamp with periodic refresh
            if (lastSendTime != null) {
                var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(5_000)
                        currentTime = System.currentTimeMillis()
                    }
                }
                Text(
                    text = "Last sent: ${formatTelemetryRelativeTime(lastSendTime, currentTime)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Divider between send and receive sections
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        // Request toggle (receive from collector)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = null,
                    indication = null,
                    enabled = collectorAddress != null,
                ) { onRequestEnabledChange(!requestEnabled) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Receive locations from group",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (collectorAddress != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = "Get everyone's location periodically",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = requestEnabled,
                onCheckedChange = null,
                enabled = collectorAddress != null,
            )
        }

        // Request interval chips
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Request every: ${formatTelemetryIntervalDisplay(requestIntervalSeconds)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (requestEnabled && collectorAddress != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TelemetryIntervalChip(
                    label = "5min",
                    selected = requestIntervalSeconds == 300,
                    enabled = requestEnabled && collectorAddress != null,
                    onClick = { onRequestIntervalChange(300) },
                )
                TelemetryIntervalChip(
                    label = "15min",
                    selected = requestIntervalSeconds == 900,
                    enabled = requestEnabled && collectorAddress != null,
                    onClick = { onRequestIntervalChange(900) },
                )
                TelemetryIntervalChip(
                    label = "30min",
                    selected = requestIntervalSeconds == 1800,
                    enabled = requestEnabled && collectorAddress != null,
                    onClick = { onRequestIntervalChange(1800) },
                )
                TelemetryIntervalChip(
                    label = "1hr",
                    selected = requestIntervalSeconds == 3600,
                    enabled = requestEnabled && collectorAddress != null,
                    onClick = { onRequestIntervalChange(3600) },
                )
            }
        }

        // Request Now button with last request time
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = {
                    android.util.Log.d("LocationSharingCard", "Request Now clicked! collectorAddress=$collectorAddress, isRequesting=$isRequesting")
                    onRequestNow()
                },
                enabled = !isRequesting && collectorAddress != null,
            ) {
                if (isRequesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Requesting...")
                } else {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Request Now")
                }
            }
            // Last request timestamp with periodic refresh
            if (lastRequestTime != null) {
                var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(5_000)
                        currentTime = System.currentTimeMillis()
                    }
                }
                Text(
                    text = "Last received: ${formatTelemetryRelativeTime(lastRequestTime, currentTime)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TelemetryIntervalChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
    )
}

@Composable
private fun CollectorAddressInput(
    addressInput: String,
    onAddressChange: (String) -> Unit,
    onConfirm: (String) -> Unit,
) {
    val validationResult = DestinationHashValidator.validate(addressInput)
    val isValid = validationResult is DestinationHashValidator.ValidationResult.Valid
    val errorMessage = (validationResult as? DestinationHashValidator.ValidationResult.Error)?.message

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Group Host",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )

        OutlinedTextField(
            value = addressInput,
            onValueChange = { input ->
                // Only allow hex characters, up to 32 chars
                val filtered = input.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
                    .take(32)
                onAddressChange(filtered)

                // Auto-confirm when valid 32-char address is entered
                if (filtered.length == 32) {
                    val result = DestinationHashValidator.validate(filtered)
                    if (result is DestinationHashValidator.ValidationResult.Valid) {
                        onConfirm(result.normalizedHash)
                    }
                }
            },
            label = { Text("Destination Hash") },
            placeholder = { Text("32-character hex") },
            singleLine = true,
            isError = addressInput.isNotEmpty() && !isValid && addressInput.length == 32,
            supportingText = {
                when {
                    addressInput.isEmpty() -> Text("Enter the collector's destination hash")
                    !isValid && errorMessage != null -> Text(errorMessage)
                    else -> Text(DestinationHashValidator.getCharacterCount(addressInput))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Format a timestamp as relative time (e.g., "2 minutes ago", "Just now").
 */
private fun formatTelemetryRelativeTime(
    timestamp: Long,
    now: Long = System.currentTimeMillis(),
): String {
    val diff = now - timestamp

    return when {
        diff < 5_000 -> "Just now"
        diff < 60_000 -> "${diff / 1000} seconds ago"
        diff < 120_000 -> "1 minute ago"
        diff < 3600_000 -> "${diff / 60_000} minutes ago"
        diff < 7200_000 -> "1 hour ago"
        diff < 86400_000 -> "${diff / 3600_000} hours ago"
        else -> "${diff / 86400_000} days ago"
    }
}

/**
 * Format interval in seconds to a readable string.
 */
private fun formatTelemetryIntervalDisplay(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60

    return when {
        seconds < 60 -> "${seconds}s"
        hours > 0 && minutes == 0 && secs == 0 -> "${hours}hr"
        hours > 0 -> "${hours}h ${minutes}m"
        secs == 0 -> "${minutes}min"
        else -> "${minutes}m ${secs}s"
    }
}
