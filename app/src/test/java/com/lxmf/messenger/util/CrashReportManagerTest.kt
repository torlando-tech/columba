package com.lxmf.messenger.util

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CrashReportManagerTest {
    private lateinit var context: Context
    private lateinit var crashReportManager: CrashReportManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        crashReportManager = CrashReportManager(context)
    }

    // ========== Privacy Filtering Tests ==========

    @Test
    fun `filterSensitiveData truncates long hashes to 8 characters`() {
        val input = "Identity hash: 1234567890abcdef1234567890abcdef"
        val filtered = crashReportManager.filterSensitiveData(input)

        assertTrue(filtered.contains("12345678..."))
        assertFalse(filtered.contains("1234567890abcdef1234567890abcdef"))
    }

    @Test
    fun `filterSensitiveData preserves short hashes`() {
        val input = "Short hash: abcd1234"
        val filtered = crashReportManager.filterSensitiveData(input)

        // Hashes under 16 chars should be preserved
        assertTrue(filtered.contains("abcd1234"))
    }

    @Test
    fun `filterSensitiveData redacts IP addresses`() {
        val input = "Connected to server at 192.168.1.100 on port 8080"
        val filtered = crashReportManager.filterSensitiveData(input)

        assertTrue(filtered.contains("[IP_REDACTED]"))
        assertFalse(filtered.contains("192.168.1.100"))
    }

    @Test
    fun `filterSensitiveData redacts home directory paths`() {
        val input = "File location: /home/username/documents/secret.txt"
        val filtered = crashReportManager.filterSensitiveData(input)

        assertTrue(filtered.contains("/home/[USER]/"))
        assertFalse(filtered.contains("/home/username/"))
    }

    @Test
    fun `filterSensitiveData redacts storage paths`() {
        val input = "Saved to /storage/emulated/0/Download/file.txt"
        val filtered = crashReportManager.filterSensitiveData(input)

        assertTrue(filtered.contains("/storage/[REDACTED]/"))
        assertFalse(filtered.contains("/storage/emulated/0/"))
    }

    @Test
    fun `filterSensitiveData redacts cryptographic keys`() {
        // 64-char hex string (typical key length)
        val key = "a".repeat(64)
        val input = "Private key: $key"
        val filtered = crashReportManager.filterSensitiveData(input)

        assertTrue(filtered.contains("[KEY_REDACTED]"))
        assertFalse(filtered.contains(key))
    }

    @Test
    fun `filterSensitiveData handles multiple patterns in same text`() {
        val input = """
            User: /home/john/app
            Server: 10.0.0.1
            Hash: 1234567890abcdef1234567890abcdef
        """.trimIndent()
        val filtered = crashReportManager.filterSensitiveData(input)

        assertTrue(filtered.contains("/home/[USER]/"))
        assertTrue(filtered.contains("[IP_REDACTED]"))
        assertTrue(filtered.contains("12345678..."))
    }

    @Test
    fun `filterSensitiveData handles empty string`() {
        val filtered = crashReportManager.filterSensitiveData("")
        assertEquals("", filtered)
    }

    @Test
    fun `filterSensitiveData preserves normal text`() {
        val input = "This is a normal log message with no sensitive data"
        val filtered = crashReportManager.filterSensitiveData(input)

        assertEquals(input, filtered)
    }

    // ========== Crash Data Persistence Tests ==========

    @Test
    fun `hasPendingCrashReport returns false when no crash data exists`() {
        assertFalse(crashReportManager.hasPendingCrashReport())
    }

    @Test
    fun `getPendingCrashReport returns null when no crash data exists`() {
        assertNull(crashReportManager.getPendingCrashReport())
    }

    @Test
    fun `clearPendingCrashReport completes without error when no crash exists`() {
        // Should not throw
        crashReportManager.clearPendingCrashReport()
        assertFalse(crashReportManager.hasPendingCrashReport())
    }

    // ========== Crash Report Generation Tests ==========

    @Test
    fun `generateBugReport includes system info`() = runTest {
        val systemInfo = createTestSystemInfo()
        val report = crashReportManager.generateBugReport(systemInfo)

        assertTrue(report.contains("## Bug Report"))
        assertTrue(report.contains("### System Information"))
        assertTrue(report.contains("**Columba**: 3.0.7 (30007)"))
        assertTrue(report.contains("**Android**: 14 (API 34)"))
        assertTrue(report.contains("**Device**: Pixel 7 by Google"))
    }

    @Test
    fun `generateBugReport includes protocol versions`() = runTest {
        val systemInfo = createTestSystemInfo()
        val report = crashReportManager.generateBugReport(systemInfo)

        assertTrue(report.contains("### Protocol Versions"))
        assertTrue(report.contains("**Reticulum**: 0.8.5"))
        assertTrue(report.contains("**LXMF**: 0.5.4"))
    }

    @Test
    fun `generateBugReport truncates identity hash for privacy`() = runTest {
        val systemInfo = createTestSystemInfo()
        val report = crashReportManager.generateBugReport(systemInfo)

        // The 32-char identity hash should be truncated
        assertTrue(report.contains("**Identity**: a1b2c3d4..."))
        assertFalse(report.contains("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"))
    }

    @Test
    fun `generateBugReport includes crash info when provided`() = runTest {
        val systemInfo = createTestSystemInfo()
        val crashReport = CrashReport(
            timestamp = 1705591935000L, // 2024-01-18 14:32:15 UTC
            exceptionClass = "java.lang.NullPointerException",
            message = "Attempt to invoke method on null object",
            stackTrace = "java.lang.NullPointerException\n\tat com.example.Test.method(Test.kt:42)",
            logsAtCrash = "01-18 14:32:14 W Test: Warning message",
        )

        val report = crashReportManager.generateBugReport(systemInfo, crashReport)

        assertTrue(report.contains("### Crash Information"))
        assertTrue(report.contains("**Exception**: `java.lang.NullPointerException`"))
        assertTrue(report.contains("**Message**: Attempt to invoke method on null object"))
        assertTrue(report.contains("### Stack Trace"))
        assertTrue(report.contains("com.example.Test.method(Test.kt:42)"))
    }

    @Test
    fun `generateBugReport includes logs section`() = runTest {
        val systemInfo = createTestSystemInfo()
        val report = crashReportManager.generateBugReport(systemInfo)

        assertTrue(report.contains("### Recent Logs"))
        assertTrue(report.contains("last ${CrashReportManager.MAX_LOG_LINES} lines"))
    }

    @Test
    fun `generateBugReport filters sensitive data in logs`() = runTest {
        val systemInfo = createTestSystemInfo()
        val crashReport = CrashReport(
            timestamp = System.currentTimeMillis(),
            exceptionClass = "TestException",
            message = null,
            stackTrace = "at Test.method(Test.kt:1)",
            logsAtCrash = "Connected to 192.168.1.1 with hash 1234567890abcdef1234567890abcdef",
        )

        val report = crashReportManager.generateBugReport(systemInfo, crashReport)

        assertTrue(report.contains("[IP_REDACTED]"))
        assertTrue(report.contains("12345678..."))
    }

    // ========== Handler Installation Tests ==========

    @Test
    fun `installCrashHandler sets default exception handler`() {
        val handlerBefore = Thread.getDefaultUncaughtExceptionHandler()

        crashReportManager.installCrashHandler()

        val handlerAfter = Thread.getDefaultUncaughtExceptionHandler()
        assertNotNull(handlerAfter)

        // Clean up by resetting to original handler
        Thread.setDefaultUncaughtExceptionHandler(handlerBefore)
    }

    // ========== Helper Functions ==========

    private fun createTestSystemInfo(): SystemInfo {
        return SystemInfo(
            appVersion = "3.0.7",
            appBuildCode = 30007,
            buildType = "debug",
            gitCommitHash = "abc1234",
            buildDate = "2025-01-16 10:30",
            androidVersion = "14",
            apiLevel = 34,
            deviceModel = "Pixel 7",
            manufacturer = "Google",
            identityHash = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
            reticulumVersion = "0.8.5",
            lxmfVersion = "0.5.4",
            bleReticulumVersion = "0.2.2",
        )
    }
}
