package network.columba.app.ui.screens.settings.dialogs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * One-time opt-in dialog shown on launch to existing users (those who completed onboarding
 * before anonymous crash reporting existed). Only used in the sentry flavor. Either choice
 * marks the prompt seen so it never reappears.
 */
@Composable
fun CrashReportingOptInDialog(
    onEnable: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.BugReport,
                contentDescription = null,
            )
        },
        title = { Text("Help improve Columba?") },
        text = {
            Text(
                "Columba can send anonymous crash and error reports so the developer can " +
                    "find and fix bugs. No message content, contacts, or identity " +
                    "information is ever included. You can change this anytime in " +
                    "Settings → Advanced.",
            )
        },
        confirmButton = {
            TextButton(onClick = onEnable) {
                Text("Enable")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not now")
            }
        },
    )
}
