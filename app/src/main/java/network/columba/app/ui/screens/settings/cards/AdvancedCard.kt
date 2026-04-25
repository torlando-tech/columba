package network.columba.app.ui.screens.settings.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import network.columba.app.ui.components.CollapsibleSettingsCard

/**
 * Advanced settings card — houses power-user toggles and options that most users should
 * leave alone. Sits between the RNode Flasher and About cards so it's findable but
 * out of the way.
 *
 * @param isExpanded Whether the card is currently expanded
 * @param onExpandedChange Callback when expansion state changes
 * @param transportNodeEnabled Whether transport node mode is enabled (forwards mesh traffic)
 * @param onTransportNodeToggle Callback when transport node toggle is changed
 */
@Composable
fun AdvancedCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    transportNodeEnabled: Boolean = true,
    onTransportNodeToggle: (Boolean) -> Unit = {},
) {
    CollapsibleSettingsCard(
        title = "Advanced",
        icon = Icons.Default.Tune,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
    ) {
        // Transport Node toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                    "handle its own traffic and won't relay messages for other peers. " +
                    "It's generally not recommended for mobile devices to be transport nodes. " +
                    "They are less likely to maintain a fixed position in the network, and thus " +
                    "can negatively impact multihop routing. Enabling this will increase data " +
                    "usage and battery drain. However, in a BLE-only mesh, it's required for " +
                    "multi-hop messaging.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
