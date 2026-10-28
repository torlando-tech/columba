package network.columba.app.util

import android.content.ActivityNotFoundException
import androidx.activity.result.ActivityResultLauncher

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
