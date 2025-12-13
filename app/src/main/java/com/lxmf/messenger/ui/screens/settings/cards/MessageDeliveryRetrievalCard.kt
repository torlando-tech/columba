package com.lxmf.messenger.ui.screens.settings.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Settings card for message delivery and retrieval options.
 * Allows users to configure:
 * - Default delivery method (Direct/Propagated)
 * - Retry via relay on failure toggle
 * - Auto-select nearest relay vs. manual selection
 * - View current relay info
 * - Auto-retrieve from relay toggle
 * - Retrieval interval selection
 * - Manual sync button
 */
@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongParameterList") // Settings card requires many configuration options
@Composable
fun MessageDeliveryRetrievalCard(
    defaultMethod: String,
    tryPropagationOnFail: Boolean,
    currentRelayName: String?,
    currentRelayHops: Int?,
    isAutoSelect: Boolean,
    onMethodChange: (String) -> Unit,
    onTryPropagationToggle: (Boolean) -> Unit,
    onAutoSelectToggle: (Boolean) -> Unit,
    // Retrieval settings
    autoRetrieveEnabled: Boolean,
    retrievalIntervalSeconds: Int,
    lastSyncTimestamp: Long?,
    isSyncing: Boolean,
    onAutoRetrieveToggle: (Boolean) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onSyncNow: () -> Unit,
) {
    var showMethodDropdown by remember { mutableStateOf(false) }
    var showCustomIntervalDialog by remember { mutableStateOf(false) }
    var customIntervalInput by remember { mutableStateOf("") }

    val presetIntervals = listOf(30, 60, 120, 300)

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
                    imageVector = Icons.Default.Send,
                    contentDescription = "Message Delivery & Retrieval",
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Message Delivery & Retrieval",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Description
            Text(
                text = "Configure how messages are sent and retrieved via relay.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Default delivery method selector
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Default Delivery Method",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Box {
                    OutlinedButton(
                        onClick = { showMethodDropdown = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text =
                                when (defaultMethod) {
                                    "direct" -> "Direct (Link-based)"
                                    "propagated" -> "Propagated (Via Relay)"
                                    else -> "Direct (Link-based)"
                                },
                        )
                    }
                    DropdownMenu(
                        expanded = showMethodDropdown,
                        onDismissRequest = { showMethodDropdown = false },
                    ) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("Direct (Link-based)")
                                    Text(
                                        text = "Establishes a link, unlimited size, with retries",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                onMethodChange("direct")
                                showMethodDropdown = false
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("Propagated (Via Relay)")
                                    Text(
                                        text = "Stores message on relay for offline recipients",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                onMethodChange("propagated")
                                showMethodDropdown = false
                            },
                        )
                    }
                }
            }

            // Retry via propagation toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Retry via Relay on Failure",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "If direct delivery fails, retry through relay",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = tryPropagationOnFail,
                    onCheckedChange = onTryPropagationToggle,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Relay selection section
            Text(
                text = "My Relay",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )

            // Auto-select option
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onAutoSelectToggle(true) }
                        .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = isAutoSelect,
                    onClick = { onAutoSelectToggle(true) },
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-select nearest",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (isAutoSelect && currentRelayName != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Currently: $currentRelayName",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (currentRelayHops != null) {
                                Text(
                                    text = "($currentRelayHops ${if (currentRelayHops == 1) "hop" else "hops"})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // Manual selection option
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onAutoSelectToggle(false) }
                        .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = !isAutoSelect,
                    onClick = { onAutoSelectToggle(false) },
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Use specific relay",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!isAutoSelect && currentRelayName != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = currentRelayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (currentRelayHops != null) {
                                Text(
                                    text = "($currentRelayHops ${if (currentRelayHops == 1) "hop" else "hops"})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else if (!isAutoSelect) {
                        Text(
                            text = "No relay selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Current relay display
            if (currentRelayName != null) {
                Spacer(modifier = Modifier.height(4.dp))
                CurrentRelayInfo(
                    relayName = currentRelayName,
                    hops = currentRelayHops,
                    isAutoSelected = isAutoSelect,
                )
            } else {
                Text(
                    text = "No relay configured. Select a propagation node from the Announce Stream.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Message Retrieval Section
            Text(
                text = "MESSAGE RETRIEVAL",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            // Auto-retrieve toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-retrieve from relay",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Periodically check for messages",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = autoRetrieveEnabled,
                    onCheckedChange = onAutoRetrieveToggle,
                )
            }

            // Retrieval interval chips
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Retrieval interval: ${formatIntervalDisplay(retrievalIntervalSeconds)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IntervalChip(
                        label = "30s",
                        selected = retrievalIntervalSeconds == 30,
                        enabled = autoRetrieveEnabled,
                        onClick = { onIntervalChange(30) },
                    )
                    IntervalChip(
                        label = "60s",
                        selected = retrievalIntervalSeconds == 60,
                        enabled = autoRetrieveEnabled,
                        onClick = { onIntervalChange(60) },
                    )
                    IntervalChip(
                        label = "2min",
                        selected = retrievalIntervalSeconds == 120,
                        enabled = autoRetrieveEnabled,
                        onClick = { onIntervalChange(120) },
                    )
                    IntervalChip(
                        label = "5min",
                        selected = retrievalIntervalSeconds == 300,
                        enabled = autoRetrieveEnabled,
                        onClick = { onIntervalChange(300) },
                    )
                    // Custom chip
                    FilterChip(
                        selected = !presetIntervals.contains(retrievalIntervalSeconds),
                        onClick = {
                            customIntervalInput = retrievalIntervalSeconds.toString()
                            showCustomIntervalDialog = true
                        },
                        enabled = autoRetrieveEnabled,
                        label = {
                            Text(
                                if (presetIntervals.contains(retrievalIntervalSeconds)) {
                                    "Custom"
                                } else {
                                    "Custom (${formatIntervalDisplay(retrievalIntervalSeconds)})"
                                },
                            )
                        },
                        colors =
                            FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            ),
                    )
                }
            }

            // Sync Now button
            Button(
                onClick = onSyncNow,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSyncing && currentRelayName != null,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                    ),
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Syncing...")
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sync Now")
                }
            }

            // Last sync timestamp
            if (lastSyncTimestamp != null) {
                Text(
                    text = "Last sync: ${formatRelativeTime(lastSyncTimestamp)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }

    // Custom interval dialog
    if (showCustomIntervalDialog) {
        CustomRetrievalIntervalDialog(
            customIntervalInput = customIntervalInput,
            onInputChange = { customIntervalInput = it },
            onConfirm = { value ->
                onIntervalChange(value)
                showCustomIntervalDialog = false
            },
            onDismiss = { showCustomIntervalDialog = false },
        )
    }
}

@Composable
private fun IntervalChip(
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
private fun CurrentRelayInfo(
    relayName: String,
    hops: Int?,
    isAutoSelected: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Hub icon
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.tertiary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Hub,
                    contentDescription = "Relay",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onTertiary,
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = relayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (isAutoSelected) {
                        Text(
                            text = "(auto)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (hops != null) {
                    Text(
                        text = "$hops ${if (hops == 1) "hop" else "hops"} away",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Format a timestamp as relative time (e.g., "2 minutes ago", "Just now").
 */
private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
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
 * Format interval in seconds to a readable string (e.g., "30s", "2min", "5min").
 */
private fun formatIntervalDisplay(seconds: Int): String {
    return when {
        seconds < 60 -> "${seconds}s"
        seconds % 60 == 0 -> "${seconds / 60}min"
        else -> "${seconds / 60}m ${seconds % 60}s"
    }
}

@Composable
private fun CustomRetrievalIntervalDialog(
    customIntervalInput: String,
    onInputChange: (String) -> Unit,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom Retrieval Interval") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Enter retrieval interval (10-600 seconds):",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = customIntervalInput,
                    onValueChange = {
                        if (it.all { char -> char.isDigit() } && it.length <= 3) {
                            onInputChange(it)
                        }
                    },
                    label = { Text("Seconds") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = customIntervalInput.toIntOrNull()?.let { it < 10 || it > 600 } ?: false,
                    supportingText = {
                        val value = customIntervalInput.toIntOrNull()
                        when {
                            value == null && customIntervalInput.isNotEmpty() -> Text("Enter a valid number")
                            value != null && value < 10 -> Text("Minimum is 10 seconds")
                            value != null && value > 600 -> Text("Maximum is 600 seconds (10 min)")
                            value != null -> Text("= ${formatIntervalDisplay(value)}")
                            else -> {}
                        }
                    },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val value = customIntervalInput.toIntOrNull()
                    if (value != null && value in 10..600) {
                        onConfirm(value)
                    }
                },
                enabled = customIntervalInput.toIntOrNull()?.let { it in 10..600 } ?: false,
            ) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
