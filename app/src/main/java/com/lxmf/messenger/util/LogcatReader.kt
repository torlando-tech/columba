package com.lxmf.messenger.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Utility for safely reading logcat output.
 *
 * Reads recent logs from the current process for inclusion in bug reports.
 * All reads are performed on IO dispatcher to avoid blocking the main thread.
 */
object LogcatReader {
    private const val TAG = "LogcatReader"
    private const val DEFAULT_MAX_LINES = 500

    /**
     * Log priority levels matching logcat's priority filter.
     */
    enum class LogPriority(val flag: String) {
        VERBOSE("V"),
        DEBUG("D"),
        INFO("I"),
        WARN("W"),
        ERROR("E"),
    }

    /**
     * Read recent logs from the current process.
     *
     * @param maxLines Maximum number of log lines to read (default: 500)
     * @param minPriority Minimum log priority level (default: WARN)
     * @return Log output as a string, or empty string on error
     */
    suspend fun readRecentLogs(
        maxLines: Int = DEFAULT_MAX_LINES,
        minPriority: LogPriority = LogPriority.WARN,
    ): String = withContext(Dispatchers.IO) {
        try {
            val pid = android.os.Process.myPid()

            // Build logcat command:
            // -d: dump and exit (don't block)
            // -t N: show last N lines
            // --pid=PID: filter to current process only
            // *:PRIORITY: minimum priority level
            val command = arrayOf(
                "logcat",
                "-d",
                "-t",
                maxLines.toString(),
                "--pid=$pid",
                "*:${minPriority.flag}",
            )

            val process = Runtime.getRuntime().exec(command)

            // Read output with timeout
            val output = StringBuilder()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.appendLine(line)
                }
            }

            // Wait for process to complete with timeout
            val exitCode = try {
                process.waitFor()
            } catch (e: InterruptedException) {
                process.destroy()
                Log.w(TAG, "Logcat process interrupted")
                return@withContext ""
            }

            if (exitCode != 0) {
                Log.w(TAG, "Logcat exited with code $exitCode")
            }

            output.toString().trim()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read logcat: ${e.message}", e)
            ""
        }
    }

    /**
     * Read recent logs with all priorities (for comprehensive bug reports).
     *
     * @param maxLines Maximum number of log lines to read
     * @return Log output as a string, or empty string on error
     */
    suspend fun readAllRecentLogs(maxLines: Int = DEFAULT_MAX_LINES): String {
        return readRecentLogs(maxLines, LogPriority.DEBUG)
    }
}
