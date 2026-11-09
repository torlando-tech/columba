package network.columba.app.ui.screens.settings.cards

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
import androidx.compose.ui.res.stringResource
import network.columba.app.R
import network.columba.app.ui.components.CollapsibleSettingsCard

@Composable
fun NotificationSettingsCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    notificationsEnabled: Boolean,
    onNotificationsEnabledChange: (Boolean) -> Unit,
    onManageClick: () -> Unit,
) {
    CollapsibleSettingsCard(
        title = stringResource(R.string.settings_notif_card_title),
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
                    stringResource(R.string.settings_notif_card_enabled_hint)
                } else {
                    stringResource(R.string.settings_notif_card_disabled_hint)
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
            Text(stringResource(R.string.settings_notif_card_manage))
        }
    }
}
