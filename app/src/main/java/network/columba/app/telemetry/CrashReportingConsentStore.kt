package network.columba.app.telemetry

import android.content.Context

/** Durable authorization and cleanup journal shared by the consent coordinator and reporter. */
internal class CrashReportingConsentStore(context: Context) {
    // Preserve the existing consent key and file; local manual crash reports also use this file.
    private val prefs = context.getSharedPreferences("crash_data", Context.MODE_PRIVATE)

    val granted: Boolean get() = prefs.getBoolean("crash_reporting_consent", false)
    val cachePurgeRequired: Boolean get() = prefs.getBoolean(KEY_CACHE_PURGE_REQUIRED, false)

    fun writeConsent(granted: Boolean) {
        val editor = prefs.edit().putBoolean("crash_reporting_consent", granted)
        // One atomic commit: a later grant must not erase an interrupted revocation's journal.
        if (!granted) editor.putBoolean(KEY_CACHE_PURGE_REQUIRED, true)
        check(editor.commit()) { "Unable to persist crash reporting consent" }
    }

    fun requireCachePurge() {
        check(prefs.edit().putBoolean(KEY_CACHE_PURGE_REQUIRED, true).commit()) {
            "Unable to persist crash reporting cleanup requirement"
        }
    }

    fun markCachePurged() {
        check(prefs.edit().putBoolean(KEY_CACHE_PURGE_REQUIRED, false).commit()) {
            "Unable to persist completed crash reporting cleanup"
        }
    }

    companion object {
        private const val KEY_CACHE_PURGE_REQUIRED = "crash_reporting_cache_purge_required"
    }
}
