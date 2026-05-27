package network.columba.app.util

import android.content.ActivityNotFoundException
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
 * (e.g. heavily customised AOSP / regional Xiaomi / Huawei builds). On those,
 * [Context.startActivity] with [Intent.ACTION_VIEW] throws
 * [ActivityNotFoundException] — and on some MIUI/EMUI variants [SecurityException]
 * — for the same "no handler" scenario, crashing the app. This helper swallows
 * both so the caller can degrade gracefully.
 *
 * The clipboard is intentionally left untouched: callers may have already placed
 * their own payload there (e.g. a bug report) that must not be clobbered.
 *
 * @return `true` if an external handler was launched, `false` if none could be.
 */
fun safeOpenUrl(context: Context, url: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
