package com.lxmf.messenger.ui.screens.settings.cards

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lxmf.messenger.ui.components.CollapsibleSettingsCard

@Composable
fun NotificationSettingsCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    notificationsEnabled: Boolean,
    onNotificationsEnabledChange: (Boolean) -> Unit,
    onManageClick: () -> Unit,
) {
    CollapsibleSettingsCard(
        title = "Notifications",
        icon = Icons.Default.Notifications,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        headerAction = {
            Switch(
                checked = notificationsEnabled,
                onCheckedChange = onNotificationsEnabledChange,
            )
        },
    ) {
        // Description
        Text(
            text =
                if (notificationsEnabled) {
                    "Manage notification preferences for messages, announces, and Bluetooth events."
                } else {
                    "All notifications are disabled. Enable to receive alerts for messages and events."
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Manage button
        Button(
            onClick = onManageClick,
            modifier = Modifier.fillMaxWidth(),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
        ) {
            Text("Manage Notifications")
        }
    }
}
