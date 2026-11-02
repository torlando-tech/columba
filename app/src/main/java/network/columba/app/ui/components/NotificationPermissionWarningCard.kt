package network.columba.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Persistent warning shown when notifications are enabled in app settings but the
 * Android 13+ POST_NOTIFICATIONS runtime permission is denied (TOR-31).
 *
 * The app previously showed the master toggle as ON with no indication that Android
 * was blocking every notification, so users had to discover the off/on toggle
 * workaround to get the system grant dialog. This card makes the mismatch visible
 * and actionable: while the dialog can still appear it offers "Grant permission";
 * once Android has stopped showing it ([permanentlyDenied]) it offers
 * "Open app settings" instead of a dead-end dialog.
 */
@Composable
fun NotificationPermissionWarningCard(
    permanentlyDenied: Boolean,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.NotificationsOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = "Notifications are blocked",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Text(
                text =
                    if (permanentlyDenied) {
                        "Android is no longer showing the permission dialog. " +
                            "Grant the notification permission in system settings to receive notifications."
                    } else {
                        "Notifications are enabled, but Android is blocking them. " +
                            "Grant the notification permission to receive them."
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Button(onClick = onAction) {
                Text(if (permanentlyDenied) "Open app settings" else "Grant permission")
            }
        }
    }
}
