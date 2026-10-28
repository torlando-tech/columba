package network.columba.app.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import network.columba.app.R

/**
 * Launches a document/file picker with [input], never throwing when no
 * activity can handle the picker intent.
 *
 * Devices that ship without DocumentsUI (e.g. Amlogic TV boxes, Sentry
 * COLUMBA-EG) resolve nothing for SAF/document-picker actions such as
 * ACTION_CREATE_DOCUMENT or ACTION_GET_CONTENT, so
 * [ActivityResultLauncher.launch] throws [ActivityNotFoundException] and
 * crashes the app. Callers must surface a user-visible fallback message
 * when this returns `false`.
 *
 * @return `true` if the picker was launched, `false` if no activity could handle it.
 */
fun <I> ActivityResultLauncher<I>.launchSafely(input: I): Boolean =
    try {
        launch(input)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

/**
 * Shows the standard user-visible fallback for an unresolvable document
 * picker (Sentry COLUMBA-EG) on screens without a snackbar host.
 */
fun Context.showNoFileManagerToast() {
    Toast.makeText(this, getString(R.string.error_no_file_manager), Toast.LENGTH_SHORT).show()
}
