package com.lxmf.messenger.util

import android.Manifest
import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Unit tests for NotificationPermissionManager.
 *
 * Tests cover:
 * - hasPermission behavior on Android 12 and below (always true)
 * - hasPermission behavior on Android 13+ (permission check)
 * - needsPermissionRequest behavior on different API levels
 * - getRequiredPermission return values
 *
 * Uses Robolectric with @Config(sdk = [...]) to test different Android versions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotificationPermissionManagerTest {
    // ========== hasPermission Tests - Android 12 and below ==========

    @Test
    @Config(sdk = [31]) // Android 12
    fun `hasPermission returns true on Android 12 regardless of permission state`() {
        val context = RuntimeEnvironment.getApplication()

        // Even without granting permission, should return true on Android 12
        val result = NotificationPermissionManager.hasPermission(context)

        assertTrue("hasPermission should return true on Android 12", result)
    }

    @Test
    @Config(sdk = [30]) // Android 11
    fun `hasPermission returns true on Android 11`() {
        val context = RuntimeEnvironment.getApplication()

        val result = NotificationPermissionManager.hasPermission(context)

        assertTrue("hasPermission should return true on Android 11", result)
    }

    // ========== hasPermission Tests - Android 13+ ==========

    @Test
    @Config(sdk = [33]) // Android 13
    fun `hasPermission returns true on Android 13 when permission granted`() {
        val context = RuntimeEnvironment.getApplication()
        val app = shadowOf(context as Application)
        app.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val result = NotificationPermissionManager.hasPermission(context)

        assertTrue("hasPermission should return true when permission is granted", result)
    }

    @Test
    @Config(sdk = [33]) // Android 13
    fun `hasPermission returns false on Android 13 when permission not granted`() {
        val context = RuntimeEnvironment.getApplication()
        val app = shadowOf(context as Application)
        app.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val result = NotificationPermissionManager.hasPermission(context)

        assertFalse("hasPermission should return false when permission is not granted", result)
    }

    @Test
    @Config(sdk = [34]) // Android 14
    fun `hasPermission returns false on Android 14 when permission not granted`() {
        val context = RuntimeEnvironment.getApplication()
        val app = shadowOf(context as Application)
        app.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val result = NotificationPermissionManager.hasPermission(context)

        assertFalse("hasPermission should return false on Android 14 without permission", result)
    }

    // ========== needsPermissionRequest Tests ==========

    @Test
    @Config(sdk = [31]) // Android 12
    fun `needsPermissionRequest returns false on Android 12`() {
        val context = RuntimeEnvironment.getApplication()

        val result = NotificationPermissionManager.needsPermissionRequest(context)

        assertFalse("needsPermissionRequest should return false on Android 12", result)
    }

    @Test
    @Config(sdk = [30]) // Android 11
    fun `needsPermissionRequest returns false on Android 11`() {
        val context = RuntimeEnvironment.getApplication()

        val result = NotificationPermissionManager.needsPermissionRequest(context)

        assertFalse("needsPermissionRequest should return false on Android 11", result)
    }

    @Test
    @Config(sdk = [33]) // Android 13
    fun `needsPermissionRequest returns true on Android 13 when permission not granted`() {
        val context = RuntimeEnvironment.getApplication()
        val app = shadowOf(context as Application)
        app.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val result = NotificationPermissionManager.needsPermissionRequest(context)

        assertTrue("needsPermissionRequest should return true on Android 13 without permission", result)
    }

    @Test
    @Config(sdk = [33]) // Android 13
    fun `needsPermissionRequest returns false on Android 13 when permission granted`() {
        val context = RuntimeEnvironment.getApplication()
        val app = shadowOf(context as Application)
        app.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val result = NotificationPermissionManager.needsPermissionRequest(context)

        assertFalse("needsPermissionRequest should return false when permission already granted", result)
    }

    // ========== getRequiredPermission Tests ==========

    @Test
    @Config(sdk = [31]) // Android 12
    fun `getRequiredPermission returns null on Android 12`() {
        val result = NotificationPermissionManager.getRequiredPermission()

        assertNull("getRequiredPermission should return null on Android 12", result)
    }

    @Test
    @Config(sdk = [30]) // Android 11
    fun `getRequiredPermission returns null on Android 11`() {
        val result = NotificationPermissionManager.getRequiredPermission()

        assertNull("getRequiredPermission should return null on Android 11", result)
    }

    @Test
    @Config(sdk = [33]) // Android 13
    fun `getRequiredPermission returns POST_NOTIFICATIONS on Android 13`() {
        val result = NotificationPermissionManager.getRequiredPermission()

        assertNotNull("getRequiredPermission should return a value on Android 13", result)
        assertEquals(
            "getRequiredPermission should return POST_NOTIFICATIONS",
            Manifest.permission.POST_NOTIFICATIONS,
            result,
        )
    }

    @Test
    @Config(sdk = [34]) // Android 14
    fun `getRequiredPermission returns POST_NOTIFICATIONS on Android 14`() {
        val result = NotificationPermissionManager.getRequiredPermission()

        assertNotNull("getRequiredPermission should return a value on Android 14", result)
        assertEquals(
            "getRequiredPermission should return POST_NOTIFICATIONS",
            Manifest.permission.POST_NOTIFICATIONS,
            result,
        )
    }
}
