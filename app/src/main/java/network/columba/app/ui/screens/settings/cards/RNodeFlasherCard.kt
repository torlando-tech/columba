package network.columba.app.ui.screens.settings.cards

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import network.columba.app.ui.components.CollapsibleSettingsCard

/**
 * Settings card for accessing the USB firmware flashers.
 *
 * Provides a gateway to the RNode flasher wizard, which allows users to:
 * - Flash new firmware to RNode devices
 * - Update existing RNode firmware
 * - Support for multiple board types (RAK4631, Heltec, T-Beam, T-Deck, etc.)
 *
 * @param isExpanded Whether the card is currently expanded
 * @param onExpandedChange Callback when expansion state changes
 * @param onOpenFlasher Callback when the RNode flasher is opened
 * @param onOpenPyxisUpdater Callback when the Pyxis updater is opened
 */
@Composable
fun RNodeFlasherCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onOpenFlasher: () -> Unit,
    onOpenPyxisUpdater: () -> Unit,
) {
    CollapsibleSettingsCard(
        title = "Firmware Flashers",
        icon = Icons.Default.Memory,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
    ) {
        // Description
        Text(
            text = "Flash RNode firmware or install Pyxis updates over USB.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Supported devices info
        Text(
            text = "Pyxis updates preserve device settings, identities, and conversations.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Action button
        Button(
            onClick = onOpenFlasher,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Default.Memory,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Open RNode Flasher")
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = onOpenPyxisUpdater,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Default.Memory,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Update Pyxis")
        }
    }
}
