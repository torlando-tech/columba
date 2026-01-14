package com.lxmf.messenger.ui.screens.settings.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Network settings card for viewing status and managing interfaces.
 *
 * @param onViewStatus Callback when "View Network Status" is clicked
 * @param onManageInterfaces Callback when "Manage Interfaces" is clicked
 * @param isSharedInstance When true, interface management is disabled because
 *                         Columba is connected to a shared RNS instance
 * @param sharedInstanceOnline Whether the shared instance is currently reachable.
 *                             When false and isSharedInstance is true, Columba has
 *                             switched to its own instance and interfaces can be managed.
 * @param transportNodeEnabled Whether transport node mode is enabled (forwards mesh traffic)
 * @param onTransportNodeToggle Callback when transport node toggle is changed
 * @param isLocked When true, interface management is disabled due to parental controls
 */
@Composable
fun NetworkCard(
    onViewStatus: () -> Unit,
    onManageInterfaces: () -> Unit,
    isSharedInstance: Boolean = false,
    sharedInstanceOnline: Boolean = true,
    transportNodeEnabled: Boolean = true,
    onTransportNodeToggle: (Boolean) -> Unit = {},
    isLocked: Boolean = false,
) {
    // Interface management is disabled when using a shared instance or when locked by parental controls
    val interfacesDisabled = isLocked || (isSharedInstance && sharedInstanceOnline)
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
                    imageVector = Icons.Default.Sensors,
                    contentDescription = "Network",
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Network",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Description for Network Status
            Text(
                text = "Monitor your Reticulum network status, active interfaces, BLE connections, and connection diagnostics.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Description for Manage Interfaces (changes when using shared instance or locked)
            Text(
                text =
                    when {
                        isLocked -> "Interface management is disabled by parental controls."
                        interfacesDisabled -> "Interface management is disabled while using a shared system instance."
                        else -> "Configure how your device connects to the Reticulum network. " +
                            "Add TCP connections, auto-discovery, LoRa (via RNode), or BLE interfaces."
                    },
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (interfacesDisabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            // Transport Node Toggle Section
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
                        imageVector = Icons.Default.Hub,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Transport Node",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Switch(
                    checked = transportNodeEnabled,
                    onCheckedChange = onTransportNodeToggle,
                )
            }
            Text(
                text =
                    "Forward traffic for the mesh network. When disabled, this device will only " +
                        "handle its own traffic and won't relay messages for other peers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            // Primary action - View Network Status (always enabled)
            Button(
                onClick = onViewStatus,
                modifier = Modifier.fillMaxWidth(),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("View Network Status")
            }

            // Secondary action - Manage Interfaces (disabled when using shared instance)
            OutlinedButton(
                onClick = onManageInterfaces,
                modifier = Modifier.fillMaxWidth(),
                enabled = !interfacesDisabled,
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Manage Interfaces")
            }
        }
    }
}
