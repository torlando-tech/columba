package com.lxmf.messenger.service.manager

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for LockManager.
 *
 * Tests WiFi, Multicast, and Wake lock acquisition/release.
 */
class LockManagerTest {
    private lateinit var context: Context
    private lateinit var wifiManager: WifiManager
    private lateinit var powerManager: PowerManager
    private lateinit var multicastLock: WifiManager.MulticastLock
    private lateinit var wifiLock: WifiManager.WifiLock
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var lockManager: LockManager

    @Before
    fun setup() {
        // Create mocks
        context = mockk(relaxed = true)
        wifiManager = mockk(relaxed = true)
        powerManager = mockk(relaxed = true)
        multicastLock = mockk(relaxed = true)
        wifiLock = mockk(relaxed = true)
        wakeLock = mockk(relaxed = true)

        // Setup context to return system services
        every { context.applicationContext } returns context
        every { context.getSystemService(Context.WIFI_SERVICE) } returns wifiManager
        every { context.getSystemService(Context.POWER_SERVICE) } returns powerManager

        // Setup lock creation
        every { wifiManager.createMulticastLock(any()) } returns multicastLock
        @Suppress("DEPRECATION")
        every { wifiManager.createWifiLock(any<Int>(), any()) } returns wifiLock
        every { powerManager.newWakeLock(any(), any()) } returns wakeLock

        // Initially locks are not held
        every { multicastLock.isHeld } returns false
        every { wifiLock.isHeld } returns false
        every { wakeLock.isHeld } returns false

        lockManager = LockManager(context)
    }

    @Test
    fun `acquireAll acquires all locks`() {
        lockManager.acquireAll()

        verify { multicastLock.setReferenceCounted(false) }
        verify { multicastLock.acquire() }
        verify { wifiLock.setReferenceCounted(false) }
        verify { wifiLock.acquire() }
        verify { wakeLock.setReferenceCounted(false) }
        verify { wakeLock.acquire(any()) }
    }

    @Test
    fun `releaseAll releases all held locks`() {
        // Setup locks as held
        every { multicastLock.isHeld } returns true
        every { wifiLock.isHeld } returns true
        every { wakeLock.isHeld } returns true

        // First acquire so we have lock references
        lockManager.acquireAll()
        lockManager.releaseAll()

        verify { multicastLock.release() }
        verify { wifiLock.release() }
        verify { wakeLock.release() }
    }

    @Test
    fun `releaseAll does not release unheld locks`() {
        // Locks are not held (default from setup)
        lockManager.acquireAll()

        // Now mark as not held before release
        every { multicastLock.isHeld } returns false
        every { wifiLock.isHeld } returns false
        every { wakeLock.isHeld } returns false

        lockManager.releaseAll()

        // Release should not be called when locks aren't held
        verify(exactly = 0) { multicastLock.release() }
        verify(exactly = 0) { wifiLock.release() }
        verify(exactly = 0) { wakeLock.release() }
    }

    @Test
    fun `getLockStatus returns correct status when no locks held`() {
        val status = lockManager.getLockStatus()

        assertFalse(status.multicastHeld)
        assertFalse(status.wifiHeld)
        assertFalse(status.wakeHeld)
    }

    @Test
    fun `getLockStatus returns correct status when locks held`() {
        // Acquire locks
        lockManager.acquireAll()

        // Mark as held
        every { multicastLock.isHeld } returns true
        every { wifiLock.isHeld } returns true
        every { wakeLock.isHeld } returns true

        val status = lockManager.getLockStatus()

        assertTrue(status.multicastHeld)
        assertTrue(status.wifiHeld)
        assertTrue(status.wakeHeld)
    }

    @Test
    fun `acquireAll is idempotent when locks already held`() {
        // First acquisition
        lockManager.acquireAll()

        // Mark as already held
        every { multicastLock.isHeld } returns true
        every { wifiLock.isHeld } returns true
        every { wakeLock.isHeld } returns true

        // Second acquisition should not re-acquire
        lockManager.acquireAll()

        // acquire() should only be called once
        verify(exactly = 1) { multicastLock.acquire() }
        verify(exactly = 1) { wifiLock.acquire() }
        verify(exactly = 1) { wakeLock.acquire(any()) }
    }

    @Test
    fun `LockStatus data class equals works correctly`() {
        val status1 = LockManager.LockStatus(true, false, true)
        val status2 = LockManager.LockStatus(true, false, true)
        val status3 = LockManager.LockStatus(false, false, true)

        assertEquals(status1, status2)
        assertTrue(status1 != status3)
    }
}
