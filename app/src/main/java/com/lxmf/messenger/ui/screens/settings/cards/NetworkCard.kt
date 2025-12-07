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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
 */
@Composable
fun NetworkCard(
    onViewStatus: () -> Unit,
    onManageInterfaces: () -> Unit,
    isSharedInstance: Boolean = false,
) {
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

            // Description for Manage Interfaces (changes when using shared instance)
            Text(
                text =
                    if (isSharedInstance) {
                        "Interface management is disabled while using a shared system instance."
                    } else {
                        "Configure how your device connects to the Reticulum network. " +
                            "Add TCP connections, auto-discovery, or BLE interfaces."
                    },
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (isSharedInstance) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
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
                enabled = !isSharedInstance,
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
