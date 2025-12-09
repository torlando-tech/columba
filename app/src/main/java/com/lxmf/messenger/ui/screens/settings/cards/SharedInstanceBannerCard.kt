package com.lxmf.messenger.ui.screens.settings.cards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
 * Banner card displayed for shared Reticulum instance management.
 *
 * Shown when:
 * - Connected to a shared instance (e.g., from Sideband)
 * - User has opted to use Columba's own instance (so they can toggle back)
 *
 * The banner is collapsible:
 * - Collapsed: Shows current instance status with expand chevron
 * - Expanded: Shows full explanation and toggle
 */
@Composable
fun SharedInstanceBannerCard(
    isExpanded: Boolean,
    preferOwnInstance: Boolean,
    isUsingSharedInstance: Boolean,
    rpcKey: String?,
    sharedInstanceLost: Boolean = false,
    sharedInstanceAvailable: Boolean = false, // Used for toggle enable logic
    onExpandToggle: (Boolean) -> Unit,
    onTogglePreferOwnInstance: (Boolean) -> Unit,
    onRpcKeyChange: (String?) -> Unit,
    onSwitchToOwnInstance: () -> Unit = {},
    onDismissLostWarning: () -> Unit = {},
) {
    // Use error color when shared instance is lost, primary otherwise
    val containerColor =
        if (sharedInstanceLost) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer
        }
    val contentColor =
        if (sharedInstanceLost) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer
        }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onExpandToggle(!isExpanded) },
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = containerColor,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header row (always visible)
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
                        imageVector =
                            if (sharedInstanceLost) {
                                Icons.Default.LinkOff
                            } else {
                                Icons.Default.Link
                            },
                        contentDescription = "Instance Mode",
                        tint = contentColor,
                    )
                    Text(
                        text =
                            when {
                                sharedInstanceLost -> "Shared Instance Disconnected"
                                isUsingSharedInstance -> "Connected to Shared Instance"
                                else -> "Using Columba's Own Instance"
                            },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = contentColor,
                    )
                }
                Icon(
                    imageVector =
                        if (isExpanded) {
                            Icons.Default.KeyboardArrowUp
                        } else {
                            Icons.Default.KeyboardArrowDown
                        },
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = contentColor,
                )
            }

            // Expanded content
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Show different content based on state
                    if (sharedInstanceLost) {
                        // Lost state - show warning and action buttons
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Warning",
                                tint = contentColor,
                            )
                            Text(
                                text =
                                    "The shared Reticulum instance (e.g., Sideband) appears " +
                                        "to be offline. Columba cannot send or receive messages.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = contentColor,
                            )
                        }

                        Text(
                            text =
                                "You can switch to Columba's own instance to restore " +
                                    "connectivity, or wait for the shared instance to come back online.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = contentColor,
                        )

                        // Action buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = onDismissLostWarning,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Wait")
                            }
                            Button(
                                onClick = onSwitchToOwnInstance,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Use Own Instance")
                            }
                        }
                    } else {
                        // Normal state
                        Text(
                            text =
                                if (isUsingSharedInstance) {
                                    "Another app (e.g., Sideband) is managing the Reticulum network " +
                                        "on this device. Columba is using that connection."
                                } else {
                                    "Columba is running its own Reticulum instance. Toggle off to use " +
                                        "a shared instance if available."
                                },
                            style = MaterialTheme.typography.bodyMedium,
                            color = contentColor,
                        )

                        // Bullet points (only when using shared instance)
                        if (isUsingSharedInstance) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = "• Network interfaces are managed by the other app",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = contentColor,
                                )
                                Text(
                                    text = "• Your identities and messages remain private to Columba",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = contentColor,
                                )
                                Text(
                                    text =
                                        "• BLE connections to other Columba users require " +
                                            "Columba's own instance",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = contentColor,
                                )
                            }
                        }

                        // Toggle for using own instance
                        // Can only toggle OFF (to shared) if a shared instance is available
                        val canSwitchToShared = isUsingSharedInstance || sharedInstanceAvailable
                        // Enable toggle if: turning ON (always ok) OR turning OFF with shared available
                        val toggleEnabled = !preferOwnInstance || canSwitchToShared
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "Use Columba's own instance",
                                style = MaterialTheme.typography.bodyMedium,
                                color =
                                    if (toggleEnabled) {
                                        contentColor
                                    } else {
                                        contentColor.copy(alpha = 0.5f)
                                    },
                            )
                            Switch(
                                checked = preferOwnInstance,
                                onCheckedChange = onTogglePreferOwnInstance,
                                enabled = toggleEnabled,
                            )
                        }

                        // Hint text about restart
                        Text(
                            text = "Service will restart automatically",
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor.copy(alpha = 0.7f),
                        )

                        // Show hint when toggle is disabled (ON but can't turn OFF)
                        if (preferOwnInstance && !canSwitchToShared) {
                            Text(
                                text = "No shared instance detected",
                                style = MaterialTheme.typography.bodySmall,
                                color = contentColor.copy(alpha = 0.7f),
                            )
                        }

                        // RPC Key input (only when using shared instance)
                        if (isUsingSharedInstance && !preferOwnInstance) {
                            var rpcKeyInput by remember { mutableStateOf(rpcKey ?: "") }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "RPC Key (from Sideband → Connectivity)",
                                style = MaterialTheme.typography.labelMedium,
                                color = contentColor,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                OutlinedTextField(
                                    value = rpcKeyInput,
                                    onValueChange = { rpcKeyInput = it },
                                    modifier = Modifier.weight(1f),
                                    placeholder = {
                                        Text(
                                            "Paste config or key...",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    },
                                    singleLine = false,
                                    maxLines = 3,
                                    textStyle = MaterialTheme.typography.bodySmall,
                                )
                                Button(
                                    onClick = {
                                        onRpcKeyChange(rpcKeyInput.ifEmpty { null })
                                    },
                                    enabled = rpcKeyInput != (rpcKey ?: ""),
                                ) {
                                    Text("Save")
                                }
                            }
                            Text(
                                text = "Paste full config or just the hex key",
                                style = MaterialTheme.typography.bodySmall,
                                color = contentColor.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }
        }
    }
}
