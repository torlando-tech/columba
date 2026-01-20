package com.lxmf.messenger.ui.screens.settings.cards

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import com.lxmf.messenger.ui.components.CollapsibleSettingsCard
import com.lxmf.messenger.util.NotificationPermissionManager

@Composable
fun NotificationSettingsCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    notificationsEnabled: Boolean,
    onNotificationsEnabledChange: (Boolean) -> Unit,
    onManageClick: () -> Unit,
) {
    val context = LocalContext.current

    // Permission launcher for Android 13+
    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            // Call the callback with the permission result
            // If denied, notifications stay disabled
            onNotificationsEnabledChange(isGranted)
        }

    CollapsibleSettingsCard(
        title = "Notifications",
        icon = Icons.Default.Notifications,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        headerAction = {
            Switch(
                checked = notificationsEnabled,
                onCheckedChange = { enabled ->
                    if (enabled && NotificationPermissionManager.needsPermissionRequest(context)) {
                        // Request permission before enabling on Android 13+
                        NotificationPermissionManager.getRequiredPermission()?.let { permission ->
                            permissionLauncher.launch(permission)
                        }
                    } else {
                        // Permission already granted, not needed, or disabling
                        onNotificationsEnabledChange(enabled)
                    }
                },
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
