package network.columba.app.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * Shared URL for filing bug reports against Columba on GitHub.
 */
const val GITHUB_NEW_ISSUE_URL: String = "https://github.com/torlando-tech/columba/issues/new"

/**
 * Attempts to open the given [url] in an external handler (browser, etc.).
 *
 * Many Android devices ship without a default browser or web-capable activity
 * (e.g. heavily customised AOSP / regional Xiaomi builds). Calling
 * [Context.startActivity] with [Intent.ACTION_VIEW] on those devices throws
 * [ActivityNotFoundException] and crashes the app.
 *
 * This helper:
 *  - sets [Intent.FLAG_ACTIVITY_NEW_TASK] so it can be safely launched from
 *    non-Activity contexts,
 *  - swallows [ActivityNotFoundException],
 *  - copies the URL to the clipboard as a fallback so the user can still reach
 *    the destination manually.
 *
 * @return `true` if an external handler was launched, `false` if the URL was
 *         copied to the clipboard instead.
 */
fun safeOpenUrl(context: Context, url: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        copyToClipboard(context, label = "URL", text = url)
        false
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
}
