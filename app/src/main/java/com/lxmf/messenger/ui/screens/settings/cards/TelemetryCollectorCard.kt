package com.lxmf.messenger.ui.screens.settings.cards

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.lxmf.messenger.data.model.EnrichedContact
import com.lxmf.messenger.ui.components.ProfileIcon
import kotlinx.coroutines.delay

/**
 * Settings card for configuring telemetry collector integration.
 *
 * Allows users to:
 * - Enable/disable automatic telemetry sending to a collector
 * - Configure the collector destination address
 * - Set the send interval
 * - Manually trigger a send
 *
 * The collector can respond with bulk telemetry from multiple sources,
 * which is displayed on the map as locations from different peers.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TelemetryCollectorCard(
    enabled: Boolean,
    collectorAddress: String?,
    sendIntervalSeconds: Int,
    lastSendTime: Long?,
    isSending: Boolean,
    contacts: List<EnrichedContact>,
    onEnabledChange: (Boolean) -> Unit,
    onCollectorAddressChange: (String?) -> Unit,
    onSendIntervalChange: (Int) -> Unit,
    onSendNow: () -> Unit,
) {
    var showContactPicker by remember { mutableStateOf(false) }

    // Find the selected contact name for display
    val selectedContactName = contacts.find {
        it.destinationHash.equals(collectorAddress, ignoreCase = true)
    }?.displayName

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
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.CloudUpload,
                    contentDescription = "Group Tracker",
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Group Tracker",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Description
            Text(
                text = "Share your location with a group and see where everyone is",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Enable toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Enable Collector",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Automatically send location telemetry",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                )
            }

            // Select from contacts
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Group Host",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showContactPicker = true }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = selectedContactName ?: "Select from contacts...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selectedContactName != null) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Contact picker dialog
            if (showContactPicker) {
                ContactPickerDialog(
                    contacts = contacts,
                    selectedHash = collectorAddress,
                    onContactSelected = { contact ->
                        onCollectorAddressChange(contact.destinationHash.lowercase())
                        showContactPicker = false
                    },
                    onDismiss = { showContactPicker = false },
                )
            }

            // Send interval chips
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Send interval: ${formatIntervalDisplay(sendIntervalSeconds)}",
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
                        label = "5min",
                        selected = sendIntervalSeconds == 300,
                        enabled = enabled && collectorAddress != null,
                        onClick = { onSendIntervalChange(300) },
                    )
                    IntervalChip(
                        label = "15min",
                        selected = sendIntervalSeconds == 900,
                        enabled = enabled && collectorAddress != null,
                        onClick = { onSendIntervalChange(900) },
                    )
                    IntervalChip(
                        label = "30min",
                        selected = sendIntervalSeconds == 1800,
                        enabled = enabled && collectorAddress != null,
                        onClick = { onSendIntervalChange(1800) },
                    )
                    IntervalChip(
                        label = "1hr",
                        selected = sendIntervalSeconds == 3600,
                        enabled = enabled && collectorAddress != null,
                        onClick = { onSendIntervalChange(3600) },
                    )
                }
            }

            // Send Now button
            Button(
                onClick = onSendNow,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled && !isSending && collectorAddress != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                ),
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sending...")
                } else {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
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
                    text = "Last sent: ${formatRelativeTime(lastSendTime, currentTime)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
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

/**
 * Format a timestamp as relative time (e.g., "2 minutes ago", "Just now").
 */
private fun formatRelativeTime(
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
private fun formatIntervalDisplay(seconds: Int): String {
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

/**
 * Dialog for selecting a contact as the group host/collector.
 */
@Composable
private fun ContactPickerDialog(
    contacts: List<EnrichedContact>,
    selectedHash: String?,
    onContactSelected: (EnrichedContact) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Group Host") },
        text = {
            if (contacts.isEmpty()) {
                Text(
                    text = "No contacts available. Add contacts first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                ) {
                    items(contacts.sortedBy { it.displayName.lowercase() }) { contact ->
                        ContactRow(
                            contact = contact,
                            isSelected = contact.destinationHash.equals(selectedHash, ignoreCase = true),
                            onClick = { onContactSelected(contact) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/**
 * A clickable row displaying a contact for single selection.
 */
@Composable
private fun ContactRow(
    contact: EnrichedContact,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
    ) {
        val hashBytes = contact.destinationHash
            .chunked(2)
            .mapNotNull { it.toIntOrNull(16)?.toByte() }
            .toByteArray()

        ProfileIcon(
            iconName = contact.iconName,
            foregroundColor = contact.iconForegroundColor,
            backgroundColor = contact.iconBackgroundColor,
            size = 40.dp,
            fallbackHash = hashBytes,
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = contact.displayName,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
    }
}
