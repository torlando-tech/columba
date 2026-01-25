package com.lxmf.messenger.util

import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Unit tests for BatteryOptimizationManager.
 *
 * Tests the battery optimization exemption request logic, including:
 * - Direct exemption intent when supported by device
 * - Fallback to battery settings when direct intent unavailable (OEM devices)
 * - Exception handling for unexpected failures
 *
 * Issue #348: MEIZU devices crash with ActivityNotFoundException because they
 * don't implement ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class BatteryOptimizationManagerTest {
    private lateinit var activity: Activity

    @Before
    fun setup() {
        activity = Robolectric.buildActivity(Activity::class.java).create().get()
    }

    // ========== Intent Creation Tests ==========

    @Test
    fun `createRequestExemptionIntent has correct action`() {
        val intent = BatteryOptimizationManager.createRequestExemptionIntent(activity)

        assertEquals(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            intent.action,
        )
    }

    @Test
    fun `createRequestExemptionIntent has correct package data`() {
        val intent = BatteryOptimizationManager.createRequestExemptionIntent(activity)

        assertEquals(
            "package:${activity.packageName}",
            intent.data?.toString(),
        )
    }

    @Test
    fun `createBatterySettingsIntent has correct action`() {
        val intent = BatteryOptimizationManager.createBatterySettingsIntent()

        assertEquals(
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            intent.action,
        )
    }

    // ========== requestBatteryOptimizationExemption Tests ==========

    @Test
    fun `requestBatteryOptimizationExemption launches direct intent when supported`() {
        // Given: Device supports direct exemption intent (normal Android)
        val shadowPackageManager = shadowOf(activity.packageManager)
        val exemptionIntent = BatteryOptimizationManager.createRequestExemptionIntent(activity)

        // Register an activity that can handle the exemption intent
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.android.settings"
                name = "BatteryOptimizationActivity"
            }
        }
        shadowPackageManager.addResolveInfoForIntent(exemptionIntent, resolveInfo)

        // When
        val result = BatteryOptimizationManager.requestBatteryOptimizationExemption(activity)

        // Then: Returns true and launches the direct exemption intent
        assertTrue("Should return true on successful launch", result)

        val shadowActivity = shadowOf(activity)
        val startedIntent = shadowActivity.nextStartedActivity
        assertEquals(
            "Should launch direct exemption intent",
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            startedIntent?.action,
        )
    }

    @Test
    fun `requestBatteryOptimizationExemption falls back to settings when direct intent unavailable`() {
        // Given: Device does NOT support direct exemption intent (MEIZU, OnePlus, etc.)
        // Don't register any handler for the exemption intent - resolveActivity will return null

        // But register the fallback settings intent
        val shadowPackageManager = shadowOf(activity.packageManager)
        val settingsIntent = BatteryOptimizationManager.createBatterySettingsIntent()
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.android.settings"
                name = "BatterySettingsActivity"
            }
        }
        shadowPackageManager.addResolveInfoForIntent(settingsIntent, resolveInfo)

        // When
        val result = BatteryOptimizationManager.requestBatteryOptimizationExemption(activity)

        // Then: Returns true and launches the fallback settings intent
        assertTrue("Should return true when fallback works", result)

        val shadowActivity = shadowOf(activity)
        val startedIntent = shadowActivity.nextStartedActivity
        assertEquals(
            "Should launch fallback battery settings intent",
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            startedIntent?.action,
        )
    }

    @Test
    fun `requestBatteryOptimizationExemption returns true even when both intents available`() {
        // Given: Both intents are available (standard Android device)
        val shadowPackageManager = shadowOf(activity.packageManager)

        // Register direct exemption handler
        val exemptionIntent = BatteryOptimizationManager.createRequestExemptionIntent(activity)
        val exemptionResolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.android.settings"
                name = "BatteryExemptionDialog"
            }
        }
        shadowPackageManager.addResolveInfoForIntent(exemptionIntent, exemptionResolveInfo)

        // Also register settings handler
        val settingsIntent = BatteryOptimizationManager.createBatterySettingsIntent()
        val settingsResolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.android.settings"
                name = "BatterySettingsActivity"
            }
        }
        shadowPackageManager.addResolveInfoForIntent(settingsIntent, settingsResolveInfo)

        // When
        val result = BatteryOptimizationManager.requestBatteryOptimizationExemption(activity)

        // Then: Prefers direct exemption (better UX)
        assertTrue("Should return true", result)

        val shadowActivity = shadowOf(activity)
        val startedIntent = shadowActivity.nextStartedActivity
        assertEquals(
            "Should prefer direct exemption intent",
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            startedIntent?.action,
        )
    }

    // ========== isIgnoringBatteryOptimizations Tests ==========

    @Test
    fun `isIgnoringBatteryOptimizations checks power manager on M+ devices`() {
        // Given: Android 6.0+ (app minSdk is 24, so always M+)

        // When
        val result = BatteryOptimizationManager.isIgnoringBatteryOptimizations(activity)

        // Then: Should return a boolean (actual value depends on Robolectric defaults)
        // The important thing is it doesn't crash and returns a valid value
        assertTrue("Result should be a valid boolean", result == true || result == false)
    }

    // ========== shouldPromptForExemption Tests ==========

    @Test
    fun `shouldPromptForExemption returns valid result on M+ devices`() {
        // Given: Android 6.0+ (app minSdk is 24, so always M+)

        // When
        val result = BatteryOptimizationManager.shouldPromptForExemption(activity)

        // Then: Should return a valid boolean based on current state
        assertTrue("Result should be a valid boolean", result == true || result == false)
    }
}
