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
import androidx.compose.ui.res.stringResource
import com.lxmf.messenger.R
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
        title = stringResource(R.string.notification_settings_title),
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
                    stringResource(R.string.notification_settings_enabled_description)
                } else {
                    stringResource(R.string.notification_settings_disabled_description)
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
            Text(stringResource(R.string.notification_settings_manage_action))
        }
    }
}
