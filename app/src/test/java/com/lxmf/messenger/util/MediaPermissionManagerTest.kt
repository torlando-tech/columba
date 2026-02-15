package com.lxmf.messenger.util

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MediaPermissionManagerTest {
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

    @Test
    fun `getRequiredPermissions returns non-empty list`() {
        val permissions = MediaPermissionManager.getRequiredPermissions()
        assertTrue(permissions.isNotEmpty())
    }

    @Test
    fun `getRequiredPermissions returns exactly one permission`() {
        // Regardless of API level, we only need one media permission
        val permissions = MediaPermissionManager.getRequiredPermissions()
        assertTrue(permissions.size == 1)
    }

    @Test
    fun `hasPermission returns true when all required permissions granted`() {
        val permissions = MediaPermissionManager.getRequiredPermissions()
        for (permission in permissions) {
            every {
                ContextCompat.checkSelfPermission(context, permission)
            } returns PackageManager.PERMISSION_GRANTED
        }

        assertTrue(MediaPermissionManager.hasPermission(context))
    }

    @Test
    fun `hasPermission returns false when required permission denied`() {
        val permissions = MediaPermissionManager.getRequiredPermissions()
        for (permission in permissions) {
            every {
                ContextCompat.checkSelfPermission(context, permission)
            } returns PackageManager.PERMISSION_DENIED
        }

        assertFalse(MediaPermissionManager.hasPermission(context))
    }
}
