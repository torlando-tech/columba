package com.lxmf.messenger.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Represents a captured crash report.
 */
data class CrashReport(
    val timestamp: Long,
    val exceptionClass: String,
    val message: String?,
    val stackTrace: String,
    val logsAtCrash: String?,
)

/**
 * Manages crash detection, persistence, and bug report generation.
 *
 * ## Features
 * - Installs an UncaughtExceptionHandler to capture crashes
 * - Persists crash data to SharedPreferences (survives process death)
 * - Generates privacy-filtered Markdown bug reports for GitHub
 *
 * ## Usage
 * 1. Call [installCrashHandler] early in Application.onCreate()
 * 2. On next launch, check [hasPendingCrashReport] to show dialog
 * 3. Use [generateBugReport] to create clipboard-ready Markdown
 */
@Singleton
class CrashReportManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "CrashReportManager"
        private const val PREFS_NAME = "crash_data"
        internal const val MAX_LOG_LINES = 500

        // SharedPreferences keys
        private const val KEY_HAS_CRASH = "has_crash"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_EXCEPTION_CLASS = "exception_class"
        private const val KEY_MESSAGE = "message"
        private const val KEY_STACK_TRACE = "stack_trace"
        private const val KEY_LOGS = "logs"

        // Privacy filter patterns
        private val HASH_PATTERN = Regex("([a-fA-F0-9]{16,})")
        private val IP_PATTERN = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
        private val HOME_PATH_PATTERN = Regex("/home/[^/]+/")
        private val STORAGE_PATH_PATTERN = Regex("/storage/emulated/\\d+/")
        private val KEY_PATTERN = Regex("([a-fA-F0-9]{64,})")
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    /**
     * Install the uncaught exception handler.
     * Call this early in Application.onCreate(), before other initialization.
     */
    fun installCrashHandler() {
        // Save the default handler to chain to it later
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception captured", throwable)

            try {
                // Persist crash data without logs - logs will be captured fresh when
                // the user chooses to report the bug after restart. This avoids using
                // runBlocking which can deadlock if crash occurred on main thread.
                persistCrashData(throwable, logs = null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist crash data", e)
            }

            // Chain to default handler (usually causes crash dialog / process termination)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        Log.d(TAG, "Crash handler installed")
    }

    /**
     * Check if there's a pending crash report from a previous session.
     */
    fun hasPendingCrashReport(): Boolean {
        return prefs.getBoolean(KEY_HAS_CRASH, false)
    }

    /**
     * Get the pending crash report, if any.
     */
    fun getPendingCrashReport(): CrashReport? {
        if (!hasPendingCrashReport()) return null

        return CrashReport(
            timestamp = prefs.getLong(KEY_TIMESTAMP, 0),
            exceptionClass = prefs.getString(KEY_EXCEPTION_CLASS, "Unknown") ?: "Unknown",
            message = prefs.getString(KEY_MESSAGE, null),
            stackTrace = prefs.getString(KEY_STACK_TRACE, "") ?: "",
            logsAtCrash = prefs.getString(KEY_LOGS, null),
        )
    }

    /**
     * Clear the pending crash report (call after user dismisses or reports).
     */
    fun clearPendingCrashReport() {
        prefs.edit()
            .remove(KEY_HAS_CRASH)
            .remove(KEY_TIMESTAMP)
            .remove(KEY_EXCEPTION_CLASS)
            .remove(KEY_MESSAGE)
            .remove(KEY_STACK_TRACE)
            .remove(KEY_LOGS)
            .apply()
        Log.d(TAG, "Crash report cleared")
    }

    /**
     * Capture recent logs for manual bug reports.
     * Uses DEBUG level to capture more context than just warnings/errors.
     */
    suspend fun captureRecentLogs(): String {
        return LogcatReader.readAllRecentLogs(MAX_LOG_LINES)
    }

    /**
     * Generate a Markdown-formatted bug report for GitHub.
     *
     * @param systemInfo System information from DeviceInfoUtil
     * @param crashReport Optional crash report (for post-crash reports)
     * @return Markdown-formatted bug report string
     */
    suspend fun generateBugReport(
        systemInfo: SystemInfo,
        crashReport: CrashReport? = null,
    ): String = withContext(Dispatchers.Default) {
        val logs = crashReport?.logsAtCrash ?: captureRecentLogs()
        val filteredLogs = filterSensitiveData(logs)

        buildString {
            appendLine("## Bug Report")
            appendLine()

            // System Information (using DeviceInfoUtil's format helper)
            appendLine(DeviceInfoUtil.formatForBugReport(systemInfo))
            appendLine()

            // Crash Information (if present)
            if (crashReport != null) {
                appendLine("### Crash Information")
                appendLine("**Exception**: `${filterSensitiveData(crashReport.exceptionClass)}`")
                if (crashReport.message != null) {
                    appendLine("**Message**: ${filterSensitiveData(crashReport.message)}")
                }
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                appendLine("**Time**: ${dateFormat.format(Date(crashReport.timestamp))}")
                appendLine()

                appendLine("### Stack Trace")
                appendLine("```")
                appendLine(filterSensitiveData(crashReport.stackTrace))
                appendLine("```")
                appendLine()
            }

            // Recent Logs
            appendLine("### Recent Logs (last $MAX_LOG_LINES lines)")
            appendLine("```")
            if (filteredLogs.isNotBlank()) {
                appendLine(filteredLogs)
            } else {
                appendLine("(No logs captured)")
            }
            appendLine("```")
        }.trim()
    }

    /**
     * Filter sensitive data from text for privacy.
     *
     * Applies the following filters:
     * - Truncates long hex hashes (16+ chars) to first 8 chars
     * - Redacts IP addresses
     * - Redacts home directory paths
     * - Redacts external storage paths
     * - Redacts cryptographic keys (64+ hex chars)
     */
    internal fun filterSensitiveData(text: String): String {
        var filtered = text

        // Redact cryptographic keys first (longer patterns)
        filtered = KEY_PATTERN.replace(filtered) { match ->
            "[KEY_REDACTED]"
        }

        // Truncate long hashes to 8 chars
        filtered = HASH_PATTERN.replace(filtered) { match ->
            val hash = match.value
            if (hash.length >= 16) {
                "${hash.take(8)}..."
            } else {
                hash
            }
        }

        // Redact IP addresses
        filtered = IP_PATTERN.replace(filtered, "[IP_REDACTED]")

        // Redact home directory paths
        filtered = HOME_PATH_PATTERN.replace(filtered, "/home/[USER]/")

        // Redact external storage paths
        filtered = STORAGE_PATH_PATTERN.replace(filtered, "/storage/[REDACTED]/")

        return filtered
    }

    /**
     * Persist crash data to SharedPreferences.
     */
    private fun persistCrashData(throwable: Throwable, logs: String?) {
        val stackTrace = buildString {
            appendLine(throwable.toString())
            throwable.stackTrace.forEach { element ->
                appendLine("    at $element")
            }

            // Include cause chain
            var cause = throwable.cause
            while (cause != null) {
                appendLine("Caused by: ${cause}")
                cause.stackTrace.forEach { element ->
                    appendLine("    at $element")
                }
                cause = cause.cause
            }
        }

        prefs.edit()
            .putBoolean(KEY_HAS_CRASH, true)
            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            .putString(KEY_EXCEPTION_CLASS, throwable.javaClass.name)
            .putString(KEY_MESSAGE, throwable.message)
            .putString(KEY_STACK_TRACE, stackTrace)
            .putString(KEY_LOGS, logs)
            .commit() // Use commit() instead of apply() for synchronous write before crash

        Log.d(TAG, "Crash data persisted")
    }
}
