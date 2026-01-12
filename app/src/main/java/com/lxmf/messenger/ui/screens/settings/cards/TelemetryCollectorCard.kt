package com.lxmf.messenger.ui.screens.settings.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.lxmf.messenger.util.DestinationHashValidator
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
    onEnabledChange: (Boolean) -> Unit,
    onCollectorAddressChange: (String?) -> Unit,
    onSendIntervalChange: (Int) -> Unit,
    onSendNow: () -> Unit,
) {
    var addressInput by remember { mutableStateOf(collectorAddress ?: "") }

    // Sync input with external state
    LaunchedEffect(collectorAddress) {
        addressInput = collectorAddress ?: ""
    }

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
                    contentDescription = "Telemetry Collector",
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Telemetry Collector",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Description
            Text(
                text = "Send your location to a telemetry collector and receive locations from multiple peers.",
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
                enabled = !isSending && collectorAddress != null,
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
            text = "Collector Address",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )

        OutlinedTextField(
            value = addressInput,
            onValueChange = { input ->
                // Only allow hex characters, up to 32 chars
                val filtered = input.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
                if (filtered.length <= 32) {
                    onAddressChange(filtered)
                    // Auto-confirm when valid
                    if (filtered.length == 32) {
                        val result = DestinationHashValidator.validate(filtered)
                        if (result is DestinationHashValidator.ValidationResult.Valid) {
                            onConfirm(result.normalizedHash)
                        }
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
    return when {
        seconds < 60 -> "${seconds}s"
        seconds % 60 == 0 -> "${seconds / 60}min"
        else -> "${seconds / 60}m ${seconds % 60}s"
    }
}
