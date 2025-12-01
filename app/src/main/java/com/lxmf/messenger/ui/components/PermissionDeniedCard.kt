package com.lxmf.messenger.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Card displaying a message when Bluetooth permissions are permanently denied,
 * with a button to open app settings.
 *
 * @param modifier Optional modifier for the card
 * @param message Custom message to display (defaults to standard permanently denied message)
 * @param showSettingsButton Whether to show the "Open Settings" button (default: true)
 */
@Composable
fun PermissionDeniedCard(
    modifier: Modifier = Modifier,
    message: String = getDefaultPermissionDeniedMessage(),
    showSettingsButton: Boolean = true,
) {
    val context = LocalContext.current

    Card(
        modifier = modifier.fillMaxWidth(),
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
        ) {
            // Icon and title
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Text(
                    text = "Permissions Required",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Message text
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )

            if (showSettingsButton) {
                Spacer(modifier = Modifier.height(16.dp))

                // Settings button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = { openAppSettings(context) },
                    ) {
                        Text("Open Settings")
                    }
                }
            }
        }
    }
}

/**
 * Default message for permanently denied permissions.
 */
private fun getDefaultPermissionDeniedMessage(): String {
    return """
        Bluetooth permissions have been denied. To use BLE features in Columba, please grant Bluetooth permissions in your device settings.

        Without these permissions, Columba cannot discover or connect to nearby devices via Bluetooth.
        """.trimIndent()
}

/**
 * Opens the app's settings page where the user can manually grant permissions.
 *
 * @param context Android context
 */
fun openAppSettings(context: Context) {
    val intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    context.startActivity(intent)
}
