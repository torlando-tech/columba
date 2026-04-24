package network.columba.app.ui.screens.settings.cards

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import network.columba.app.ui.components.CollapsibleSettingsCard

/**
 * Network settings card for viewing status and managing interfaces.
 *
 * @param isExpanded Whether the card is currently expanded
 * @param onExpandedChange Callback when expansion state changes
 * @param onViewStatus Callback when "View Network Status" is clicked
 * @param onManageInterfaces Callback when "Manage Interfaces" is clicked
 * @param isSharedInstance When true, interface management is disabled because
 *                         Columba is connected to a shared RNS instance
 * @param sharedInstanceOnline Whether the shared instance is currently reachable.
 *                             When false and isSharedInstance is true, Columba has
 *                             switched to its own instance and interfaces can be managed.
 */
@Composable
fun NetworkCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onViewStatus: () -> Unit,
    onManageInterfaces: () -> Unit,
    isSharedInstance: Boolean = false,
    sharedInstanceOnline: Boolean = true,
) {
    // Interface management is only disabled when actively using a shared instance
    // If shared instance went offline, we're now using our own instance
    val interfacesDisabled = isSharedInstance && sharedInstanceOnline
    CollapsibleSettingsCard(
        title = "Network",
        icon = Icons.Default.Sensors,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
    ) {
        // Description for Network Status
        Text(
            text = "Monitor your Reticulum network status, active interfaces, BLE connections, and connection diagnostics.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Description for Manage Interfaces (changes when using shared instance)
        Text(
            text =
                if (interfacesDisabled) {
                    "Interface management is disabled while using a shared system instance."
                } else {
                    "Configure how your device connects to the Reticulum network. " +
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
