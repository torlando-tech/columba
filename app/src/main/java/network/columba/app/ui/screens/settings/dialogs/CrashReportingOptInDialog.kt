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
        title = { Text(stringResource(R.string.settings_crash_optin_title)) },
        text = {
            Text(
                stringResource(R.string.settings_crash_optin_body),
            )
        },
        confirmButton = {
            TextButton(onClick = onEnable) {
                Text(stringResource(R.string.settings_crash_optin_enable))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_crash_optin_not_now))
            }
        },
    )
}
