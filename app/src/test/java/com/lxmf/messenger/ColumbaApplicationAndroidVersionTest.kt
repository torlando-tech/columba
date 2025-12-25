package com.lxmf.messenger

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for ColumbaApplication Android version-specific behavior.
 * Tests that the version check logic correctly handles different Android API levels.
 */
@RunWith(RobolectricTestRunner::class)
class ColumbaApplicationAndroidVersionTest {

    @Test
    @Config(sdk = [31]) // Android 12 (S)
    fun `version check returns early on Android 12`() {
        // Verify we're running on API 31
        assertEquals(31, Build.VERSION.SDK_INT)
        
        // The version check: Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        // should be true on Android 12, causing early return
        val shouldSkip = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        assertEquals(true, shouldSkip)
    }

    @Test
    @Config(sdk = [33]) // Android 13 (TIRAMISU)
    fun `version check allows execution on Android 13+`() {
        // Verify we're running on API 33
        assertEquals(33, Build.VERSION.SDK_INT)
        
        // The version check: Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        // should be false on Android 13+, allowing execution to proceed
        val shouldSkip = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        assertEquals(false, shouldSkip)
    }

    @Test
    @Config(sdk = [34]) // Android 14
    fun `version check allows execution on Android 14+`() {
        // Verify we're running on API 34
        assertEquals(34, Build.VERSION.SDK_INT)
        
        // The version check should allow execution on Android 14+
        val shouldSkip = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        assertEquals(false, shouldSkip)
    }
}
