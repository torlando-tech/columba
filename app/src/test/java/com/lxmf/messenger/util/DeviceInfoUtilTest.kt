package com.lxmf.messenger.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeviceInfoUtilTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `getSystemInfo returns valid SystemInfo with all fields`() {
        val info =
            DeviceInfoUtil.getSystemInfo(
                context = context,
                identityHash = "a1b2c3d4e5f6",
                reticulumVersion = "0.8.5",
                lxmfVersion = "0.5.4",
                bleReticulumVersion = "0.2.2",
            )

        assertNotNull(info.appVersion)
        assertTrue(info.appBuildCode > 0)
        assertNotNull(info.buildType)
        assertNotNull(info.gitCommitHash)
        assertNotNull(info.buildDate)
        assertNotNull(info.androidVersion)
        assertTrue(info.apiLevel > 0)
        assertNotNull(info.deviceModel)
        assertNotNull(info.manufacturer)
        assertEquals("a1b2c3d4e5f6", info.identityHash)
        assertEquals("0.8.5", info.reticulumVersion)
        assertEquals("0.5.4", info.lxmfVersion)
        assertEquals("0.2.2", info.bleReticulumVersion)
    }

    @Test
    fun `getSystemInfo handles null protocol versions`() {
        val info =
            DeviceInfoUtil.getSystemInfo(
                context = context,
                identityHash = null,
                reticulumVersion = null,
                lxmfVersion = null,
                bleReticulumVersion = null,
            )

        assertNull(info.identityHash)
        assertNull(info.reticulumVersion)
        assertNull(info.lxmfVersion)
        assertNull(info.bleReticulumVersion)
    }

    @Test
    fun `formatForClipboard returns correct format with all fields`() {
        val info =
            SystemInfo(
                appVersion = "3.0.1",
                appBuildCode = 30001,
                buildType = "debug",
                gitCommitHash = "abc1234",
                buildDate = "2025-01-16 10:30",
                androidVersion = "14",
                apiLevel = 34,
                deviceModel = "Pixel 7",
                manufacturer = "Google",
                identityHash = "a1b2c3d4",
                reticulumVersion = "1.0.4",
                lxmfVersion = "0.9.2",
                bleReticulumVersion = "0.2.2",
            )

        val formatted = DeviceInfoUtil.formatForClipboard(info)

        assertTrue(formatted.contains("Columba 3.0.1 (30001)"))
        assertTrue(formatted.contains("Build: abc1234 (2025-01-16 10:30)"))
        assertTrue(formatted.contains("Android 14 (API 34)"))
        assertTrue(formatted.contains("Device: Pixel 7 by Google"))
        assertTrue(formatted.contains("Identity: a1b2c3d4"))
        assertTrue(formatted.contains("Reticulum: 1.0.4"))
        assertTrue(formatted.contains("LXMF: 0.9.2"))
        assertTrue(formatted.contains("BLE-Reticulum: 0.2.2"))
    }

    @Test
    fun `formatForClipboard handles null protocol versions`() {
        val info =
            SystemInfo(
                appVersion = "3.0.1",
                appBuildCode = 30001,
                buildType = "release",
                gitCommitHash = "xyz9999",
                buildDate = "2025-01-16 10:30",
                androidVersion = "13",
                apiLevel = 33,
                deviceModel = "Samsung S21",
                manufacturer = "Samsung",
                identityHash = null,
                reticulumVersion = null,
                lxmfVersion = null,
                bleReticulumVersion = null,
            )

        val formatted = DeviceInfoUtil.formatForClipboard(info)

        assertTrue(formatted.contains("Columba 3.0.1 (30001)"))
        assertTrue(formatted.contains("Build: xyz9999"))
        assertTrue(formatted.contains("Android 13 (API 33)"))
        assertTrue(formatted.contains("Device: Samsung S21 by Samsung"))
        assertFalse(formatted.contains("Identity:"))
        assertFalse(formatted.contains("Reticulum:"))
        assertFalse(formatted.contains("LXMF:"))
        assertFalse(formatted.contains("BLE-Reticulum:"))
    }

    @Test
    fun `formatForClipboard has proper line breaks`() {
        val info =
            SystemInfo(
                appVersion = "3.0.1",
                appBuildCode = 30001,
                buildType = "debug",
                gitCommitHash = "abc1234",
                buildDate = "2025-01-16 10:30",
                androidVersion = "14",
                apiLevel = 34,
                deviceModel = "Pixel 7",
                manufacturer = "Google",
                identityHash = "a1b2c3d4",
                reticulumVersion = "1.0.4",
                lxmfVersion = "0.9.2",
                bleReticulumVersion = "0.2.2",
            )

        val formatted = DeviceInfoUtil.formatForClipboard(info)
        val lines = formatted.split("\n")

        // Should have 8 lines total (app, build, android, device, identity, reticulum, lxmf, ble)
        assertEquals(8, lines.size)
        assertTrue(lines[0].startsWith("Columba"))
        assertTrue(lines[1].startsWith("Build:"))
        assertTrue(lines[2].startsWith("Android"))
        assertTrue(lines[3].startsWith("Device:"))
        assertTrue(lines[4].startsWith("Identity:"))
        assertTrue(lines[5].startsWith("Reticulum:"))
        assertTrue(lines[6].startsWith("LXMF:"))
        assertTrue(lines[7].startsWith("BLE-Reticulum:"))
    }

    @Test
    fun `formatForClipboard with partial data has correct line count`() {
        val info =
            SystemInfo(
                appVersion = "3.0.1",
                appBuildCode = 30001,
                buildType = "release",
                gitCommitHash = "abc1234",
                buildDate = "2025-01-16 10:30",
                androidVersion = "14",
                apiLevel = 34,
                deviceModel = "Pixel 7",
                manufacturer = "Google",
                identityHash = "a1b2c3d4",
                reticulumVersion = null,
                lxmfVersion = null,
                bleReticulumVersion = null,
            )

        val formatted = DeviceInfoUtil.formatForClipboard(info)
        val lines = formatted.split("\n")

        // Should have 5 lines (app, build, android, device, identity) - no protocol versions
        assertEquals(5, lines.size)
    }

    @Test
    fun `buildDate format is readable`() {
        val info =
            DeviceInfoUtil.getSystemInfo(
                context = context,
                identityHash = null,
                reticulumVersion = null,
                lxmfVersion = null,
                bleReticulumVersion = null,
            )

        // Build date should match pattern YYYY-MM-DD HH:mm
        assertTrue(info.buildDate.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")))
    }
}
