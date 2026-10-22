package network.columba.app.ui.screens.settings.dialogs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import network.columba.app.R

/**
 * One-time opt-in dialog shown on launch to existing users (those who completed onboarding
 * before opt-in crash reporting existed). Only used in the sentry flavor. Either choice
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
                stringResource(R.string.crash_reporting_privacy_description),
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
