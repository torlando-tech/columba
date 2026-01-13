package com.lxmf.messenger.ui.screens.settings.cards

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutoAnnounceCard(
    enabled: Boolean,
    intervalHours: Int,
    lastAnnounceTime: Long?,
    nextAnnounceTime: Long?,
    isManualAnnouncing: Boolean,
    showManualAnnounceSuccess: Boolean,
    manualAnnounceError: String?,
    onToggle: (Boolean) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onManualAnnounce: () -> Unit,
) {
    var showCustomDialog by remember { mutableStateOf(false) }
    var customIntervalInput by remember { mutableStateOf("") }

    val presetIntervals = listOf(1, 3, 6, 12)

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
                        imageVector = Icons.Default.Sensors,
                        contentDescription = "Auto Announce",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Auto Announce",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                )
            }

            // Description
            Text(
                text =
                    "Automatically announce your presence on the network at regular intervals. " +
                        "This helps other peers discover you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Interval selector (only shown when enabled)
            if (enabled) {
                IntervalSelector(
                    intervalHours = intervalHours,
                    presetIntervals = presetIntervals,
                    onIntervalChange = onIntervalChange,
                    onCustomClick = {
                        customIntervalInput = intervalHours.toString()
                        showCustomDialog = true
                    },
                )

                AnnounceStatus(
                    lastAnnounceTime = lastAnnounceTime,
                    nextAnnounceTime = nextAnnounceTime,
                )

                ManualAnnounceSection(
                    isManualAnnouncing = isManualAnnouncing,
                    showManualAnnounceSuccess = showManualAnnounceSuccess,
                    manualAnnounceError = manualAnnounceError,
                    onManualAnnounce = onManualAnnounce,
                )
            }
        }
    }

    // Custom interval dialog
    if (showCustomDialog) {
        CustomIntervalDialog(
            customIntervalInput = customIntervalInput,
            onInputChange = { customIntervalInput = it },
            onConfirm = { value ->
                onIntervalChange(value)
                showCustomDialog = false
            },
            onDismiss = { showCustomDialog = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IntervalSelector(
    intervalHours: Int,
    presetIntervals: List<Int>,
    onIntervalChange: (Int) -> Unit,
    onCustomClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Announce Interval: $intervalHours hour${if (intervalHours != 1) "s" else ""}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presetIntervals.forEach { preset ->
                FilterChip(
                    selected = intervalHours == preset,
                    onClick = { onIntervalChange(preset) },
                    label = { Text("${preset}h") },
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ),
                )
            }
            FilterChip(
                selected = !presetIntervals.contains(intervalHours),
                onClick = onCustomClick,
                label = {
                    Text(
                        if (presetIntervals.contains(intervalHours)) {
                            "Custom"
                        } else {
                            "Custom (${intervalHours}h)"
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
}

@Composable
private fun AnnounceStatus(
    lastAnnounceTime: Long?,
    nextAnnounceTime: Long?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Show last announce time
        if (lastAnnounceTime != null) {
            val timeSinceLastAnnounce = System.currentTimeMillis() - lastAnnounceTime
            val hoursAgo = (timeSinceLastAnnounce / 3600000).toInt()
            val minutesAgo = ((timeSinceLastAnnounce % 3600000) / 60000).toInt()

            Text(
                text =
                    if (hoursAgo > 0) {
                        "Last announce: ${hoursAgo}h ${minutesAgo}m ago"
                    } else if (minutesAgo > 0) {
                        "Last announce: ${minutesAgo}m ago"
                    } else {
                        "Last announce: just now"
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "No announces sent yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Show next scheduled announce time
        if (nextAnnounceTime != null) {
            val timeUntilNext = nextAnnounceTime - System.currentTimeMillis()

            val displayText =
                if (timeUntilNext <= 0) {
                    "Next announce: soon"
                } else {
                    val hoursRemaining = (timeUntilNext / 3600000).toInt()
                    val minutesRemaining = ((timeUntilNext % 3600000) / 60000).toInt()

                    if (hoursRemaining > 0) {
                        "Next announce in: ${hoursRemaining}h ${minutesRemaining}m"
                    } else {
                        "Next announce in: ${minutesRemaining}m"
                    }
                }

            Text(
                text = displayText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun ManualAnnounceSection(
    isManualAnnouncing: Boolean,
    showManualAnnounceSuccess: Boolean,
    manualAnnounceError: String?,
    onManualAnnounce: () -> Unit,
) {
    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            OutlinedButton(
                onClick = onManualAnnounce,
                enabled = !isManualAnnouncing,
                modifier = Modifier.height(40.dp),
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.tertiary,
                    ),
            ) {
                if (isManualAnnouncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                } else {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Announce Now",
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(text = if (isManualAnnouncing) "Announcing..." else "Announce Now")
            }
        }

        if (showManualAnnounceSuccess) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Success",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Announce sent!",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (manualAnnounceError != null) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Error",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Error: $manualAnnounceError",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun CustomIntervalDialog(
    customIntervalInput: String,
    onInputChange: (String) -> Unit,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom Interval") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Enter announce interval (1-12 hours):",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = customIntervalInput,
                    onValueChange = {
                        if (it.all { char -> char.isDigit() } && it.length <= 2) {
                            onInputChange(it)
                        }
                    },
                    label = { Text("Hours") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = customIntervalInput.toIntOrNull()?.let { it < 1 || it > 12 } ?: false,
                    supportingText = {
                        if (customIntervalInput.toIntOrNull()?.let { it < 1 || it > 12 } == true) {
                            Text("Value must be between 1 and 12")
                        }
                    },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val value = customIntervalInput.toIntOrNull()
                    if (value != null && value in 1..12) {
                        onConfirm(value)
                    }
                },
                enabled = customIntervalInput.toIntOrNull()?.let { it in 1..12 } ?: false,
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
