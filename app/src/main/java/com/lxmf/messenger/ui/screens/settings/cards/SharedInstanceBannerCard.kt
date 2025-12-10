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
 * - A shared instance is available to switch to
 * - Was using shared instance but it went offline (informational state)
 *
 * The banner is collapsible:
 * - Collapsed: Shows current instance status with expand chevron
 * - Expanded: Shows full explanation and toggle (or informational message if shared went offline)
 */
@Composable
fun SharedInstanceBannerCard(
    isExpanded: Boolean,
    preferOwnInstance: Boolean,
    isUsingSharedInstance: Boolean,
    rpcKey: String?,
    wasUsingSharedInstance: Boolean = false,
    sharedInstanceOnline: Boolean = false, // Current availability from service query
    onExpandToggle: (Boolean) -> Unit,
    onTogglePreferOwnInstance: (Boolean) -> Unit,
    onRpcKeyChange: (String?) -> Unit,
) {
    // Toggle enable logic:
    // - Can always switch TO own instance
    // - Can only switch TO shared instance if it's online
    val canSwitchToShared = sharedInstanceOnline || isUsingSharedInstance
    val toggleEnabled = !preferOwnInstance || canSwitchToShared

    // Determine if this is the informational state (was using shared, now offline)
    // Note: wasUsingSharedInstance is only set when shared went offline while we were using it,
    // so we just need to check that flag and that shared is still offline
    val isInformationalState = wasUsingSharedInstance && !sharedInstanceOnline

    // Use subtle color for informational state (not error), primary otherwise
    val containerColor =
        if (isInformationalState) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.primaryContainer
        }
    val contentColor =
        if (isInformationalState) {
            MaterialTheme.colorScheme.onSurfaceVariant
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
                            if (isInformationalState) {
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
                                isInformationalState -> "Shared Instance No Longer Available"
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
                    if (isInformationalState) {
                        // Informational state - shared instance went offline, Columba restarted
                        Text(
                            text =
                                "The shared Reticulum instance (e.g., Sideband) is no longer " +
                                    "available. Columba has automatically restarted with its own " +
                                    "network interfaces.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = contentColor,
                        )

                        Text(
                            text =
                                "Your messages will continue to be sent and received. " +
                                    "If the shared instance becomes available again, you can " +
                                    "switch back to it from Settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor.copy(alpha = 0.7f),
                        )
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
                        // Enabled based on whether switching is possible:
                        // - Can always switch TO own instance (preferOwnInstance = true)
                        // - Can only switch TO shared if it's online (preferOwnInstance = false)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "Use Columba's own instance",
                                style = MaterialTheme.typography.bodyMedium,
                                color = contentColor,
                            )
                            Switch(
                                checked = preferOwnInstance,
                                onCheckedChange = onTogglePreferOwnInstance,
                                enabled = toggleEnabled,
                            )
                        }

                        // Hint text - show different message based on toggle state
                        Text(
                            text =
                                if (preferOwnInstance && !canSwitchToShared) {
                                    "No shared instance available"
                                } else {
                                    "Service will restart automatically"
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor.copy(alpha = 0.7f),
                        )

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
