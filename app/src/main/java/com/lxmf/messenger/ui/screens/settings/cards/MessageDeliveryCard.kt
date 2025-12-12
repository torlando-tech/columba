package com.lxmf.messenger.ui.screens.settings.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Settings card for message delivery options.
 * Allows users to configure:
 * - Default delivery method (Direct/Propagated)
 * - Retry via relay on failure toggle
 * - Auto-select nearest relay vs. manual selection
 * - View current relay info
 */
@Composable
fun MessageDeliveryCard(
    defaultMethod: String,
    tryPropagationOnFail: Boolean,
    currentRelayName: String?,
    currentRelayHops: Int?,
    isAutoSelect: Boolean,
    onMethodChange: (String) -> Unit,
    onTryPropagationToggle: (Boolean) -> Unit,
    onAutoSelectToggle: (Boolean) -> Unit,
) {
    var showMethodDropdown by remember { mutableStateOf(false) }

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
                    contentDescription = "Message Delivery",
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Message Delivery",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Description
            Text(
                text = "Configure how messages are sent when a direct path isn't available.",
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
                                    text = "(${currentRelayHops} ${if (currentRelayHops == 1) "hop" else "hops"})",
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
                                    text = "(${currentRelayHops} ${if (currentRelayHops == 1) "hop" else "hops"})",
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
        }
    }
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
                        text = "${hops} ${if (hops == 1) "hop" else "hops"} away",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
