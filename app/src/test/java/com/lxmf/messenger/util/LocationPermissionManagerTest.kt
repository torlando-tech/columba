package com.lxmf.messenger.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Unit tests for LocationPermissionManager.
 *
 * Tests cover:
 * - Permission status checks
 * - Permission rationale
 * - Device feature checks
 *
 * Note: getRequiredPermissions() tests are limited because Build.VERSION.SDK_INT
 * cannot be easily mocked in plain JUnit tests.
 */
class LocationPermissionManagerTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        mockkStatic(ContextCompat::class)
    }

    @After
    fun tearDown() {
        unmockkStatic(ContextCompat::class)
    }

    // ========== getRequiredPermissions Tests ==========

    @Test
    fun `getRequiredPermissions returns non-empty list`() {
        val permissions = LocationPermissionManager.getRequiredPermissions()

        assertTrue(permissions.isNotEmpty())
        assertTrue(permissions.contains(Manifest.permission.ACCESS_FINE_LOCATION))
    }

    // ========== hasPermission Tests ==========

    @Test
    fun `hasPermission returns true when FINE location granted`() {
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        } returns PackageManager.PERMISSION_GRANTED
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        } returns PackageManager.PERMISSION_DENIED

        assertTrue(LocationPermissionManager.hasPermission(context))
    }

    @Test
    fun `hasPermission returns true when COARSE location granted`() {
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        } returns PackageManager.PERMISSION_DENIED
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        } returns PackageManager.PERMISSION_GRANTED

        assertTrue(LocationPermissionManager.hasPermission(context))
    }

    @Test
    fun `hasPermission returns true when both locations granted`() {
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        } returns PackageManager.PERMISSION_GRANTED
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        } returns PackageManager.PERMISSION_GRANTED

        assertTrue(LocationPermissionManager.hasPermission(context))
    }

    @Test
    fun `hasPermission returns false when no location permissions granted`() {
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        } returns PackageManager.PERMISSION_DENIED
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        } returns PackageManager.PERMISSION_DENIED

        assertFalse(LocationPermissionManager.hasPermission(context))
    }

    // ========== hasFineLocationPermission Tests ==========

    @Test
    fun `hasFineLocationPermission returns true when FINE granted`() {
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        } returns PackageManager.PERMISSION_GRANTED

        assertTrue(LocationPermissionManager.hasFineLocationPermission(context))
    }

    @Test
    fun `hasFineLocationPermission returns false when FINE denied`() {
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        } returns PackageManager.PERMISSION_DENIED

        assertFalse(LocationPermissionManager.hasFineLocationPermission(context))
    }

    // ========== checkPermissionStatus Tests ==========

    @Test
    fun `checkPermissionStatus returns Granted when FINE granted`() {
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        } returns PackageManager.PERMISSION_GRANTED
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        } returns PackageManager.PERMISSION_DENIED

        val status = LocationPermissionManager.checkPermissionStatus(context)
        assertTrue(status is LocationPermissionManager.PermissionStatus.Granted)
    }

    @Test
    fun `checkPermissionStatus returns Granted when COARSE granted`() {
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        } returns PackageManager.PERMISSION_DENIED
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        } returns PackageManager.PERMISSION_GRANTED

        val status = LocationPermissionManager.checkPermissionStatus(context)
        assertTrue(status is LocationPermissionManager.PermissionStatus.Granted)
    }

    @Test
    fun `checkPermissionStatus returns Denied when no permissions`() {
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        } returns PackageManager.PERMISSION_DENIED
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        } returns PackageManager.PERMISSION_DENIED

        val status = LocationPermissionManager.checkPermissionStatus(context)
        assertTrue(status is LocationPermissionManager.PermissionStatus.Denied)
    }

    // ========== getPermissionRationale Tests ==========

    @Test
    fun `getPermissionRationale returns non-empty string`() {
        val rationale = LocationPermissionManager.getPermissionRationale()

        assertTrue(rationale.isNotEmpty())
        assertTrue(rationale.contains("location"))
    }

    @Test
    fun `getPermissionRationale mentions peer-to-peer sharing`() {
        val rationale = LocationPermissionManager.getPermissionRationale()

        assertTrue(rationale.contains("peer-to-peer"))
    }

    // ========== isLocationSupported Tests ==========

    @Test
    fun `isLocationSupported returns true when device has location feature`() {
        val packageManager = mockk<PackageManager>()
        every { context.packageManager } returns packageManager
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION) } returns true

        assertTrue(LocationPermissionManager.isLocationSupported(context))
    }

    @Test
    fun `isLocationSupported returns false when device lacks location feature`() {
        val packageManager = mockk<PackageManager>()
        every { context.packageManager } returns packageManager
        every { packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION) } returns false

        assertFalse(LocationPermissionManager.isLocationSupported(context))
    }
}
