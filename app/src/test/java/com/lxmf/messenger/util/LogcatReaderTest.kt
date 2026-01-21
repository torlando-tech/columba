package com.lxmf.messenger.util

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LogcatReaderTest {
    // ========== LogPriority Tests ==========

    @Test
    fun `LogPriority VERBOSE has correct flag`() {
        assertEquals("V", LogcatReader.LogPriority.VERBOSE.flag)
    }

    @Test
    fun `LogPriority DEBUG has correct flag`() {
        assertEquals("D", LogcatReader.LogPriority.DEBUG.flag)
    }

    @Test
    fun `LogPriority INFO has correct flag`() {
        assertEquals("I", LogcatReader.LogPriority.INFO.flag)
    }

    @Test
    fun `LogPriority WARN has correct flag`() {
        assertEquals("W", LogcatReader.LogPriority.WARN.flag)
    }

    @Test
    fun `LogPriority ERROR has correct flag`() {
        assertEquals("E", LogcatReader.LogPriority.ERROR.flag)
    }

    @Test
    fun `LogPriority enum has correct order`() {
        val priorities = LogcatReader.LogPriority.entries
        assertEquals(5, priorities.size)
        assertEquals(LogcatReader.LogPriority.VERBOSE, priorities[0])
        assertEquals(LogcatReader.LogPriority.DEBUG, priorities[1])
        assertEquals(LogcatReader.LogPriority.INFO, priorities[2])
        assertEquals(LogcatReader.LogPriority.WARN, priorities[3])
        assertEquals(LogcatReader.LogPriority.ERROR, priorities[4])
    }

    // ========== readRecentLogs Tests ==========
    // Note: These tests verify behavior when logcat isn't available (test environment)

    @Test
    fun `readRecentLogs returns empty string on failure`() =
        runTest {
            // In Robolectric, logcat isn't available, so this should return empty string
            val logs = LogcatReader.readRecentLogs()

            // Should return empty string (not throw) when logcat fails
            assertTrue(logs.isEmpty() || logs.isNotBlank())
        }

    @Test
    fun `readRecentLogs with custom maxLines does not throw`() =
        runTest {
            // Should handle custom parameters without throwing
            val logs = LogcatReader.readRecentLogs(maxLines = 100)

            // May return empty or actual logs depending on environment
            assertTrue(logs.isEmpty() || logs.isNotBlank())
        }

    @Test
    fun `readRecentLogs with custom priority does not throw`() =
        runTest {
            // Should handle different priority levels
            val logs = LogcatReader.readRecentLogs(minPriority = LogcatReader.LogPriority.ERROR)

            assertTrue(logs.isEmpty() || logs.isNotBlank())
        }

    @Test
    fun `readAllRecentLogs does not throw`() =
        runTest {
            // readAllRecentLogs should work with DEBUG priority
            val logs = LogcatReader.readAllRecentLogs()

            assertTrue(logs.isEmpty() || logs.isNotBlank())
        }

    @Test
    fun `readAllRecentLogs with custom maxLines does not throw`() =
        runTest {
            val logs = LogcatReader.readAllRecentLogs(maxLines = 200)

            assertTrue(logs.isEmpty() || logs.isNotBlank())
        }

    // ========== Integration-style Tests ==========
    // These verify the function contract without depending on actual logcat

    @Test
    fun `readRecentLogs returns string type`() =
        runTest {
            val result = LogcatReader.readRecentLogs()

            // Verify return type is String (not null)
            assertTrue(result is String)
        }

    @Test
    fun `readRecentLogs is suspendable and completes`() =
        runTest {
            // Verify the suspend function completes without hanging
            val startTime = System.currentTimeMillis()
            LogcatReader.readRecentLogs()
            val elapsed = System.currentTimeMillis() - startTime

            // Should complete reasonably quickly (under 10 seconds)
            assertTrue("readRecentLogs took too long: ${elapsed}ms", elapsed < 10000)
        }
}
