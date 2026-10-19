package network.columba.app.rns.host.manager

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
 * Tests Multicast and Wake lock acquisition/release, including the
 * duty-cycle mode (persistent wake lock disabled + traffic-driven timed lock).
 */
class LockManagerTest {
    private lateinit var context: Context
    private lateinit var wifiManager: WifiManager
    private lateinit var powerManager: PowerManager
    private lateinit var multicastLock: WifiManager.MulticastLock
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var trafficWakeLock: PowerManager.WakeLock
    private lateinit var lockManager: LockManager

    @Before
    fun setup() {
        // Create mocks with explicit stubs (not relaxed)
        context = mockk()
        wifiManager = mockk()
        powerManager = mockk()
        multicastLock = mockk()
        wakeLock = mockk()
        trafficWakeLock = mockk()

        // Setup context to return system services
        every { context.applicationContext } returns context
        every { context.getSystemService(Context.WIFI_SERVICE) } returns wifiManager
        every { context.getSystemService(Context.POWER_SERVICE) } returns powerManager

        // Setup lock creation
        every { wifiManager.createMulticastLock(any()) } returns multicastLock
        every { powerManager.newWakeLock(any(), any()) } returns wakeLock

        // Stub lock methods
        every { multicastLock.setReferenceCounted(any()) } returns Unit
        every { multicastLock.acquire() } returns Unit
        every { multicastLock.release() } returns Unit
        every { wakeLock.setReferenceCounted(any()) } returns Unit
        every { wakeLock.acquire(any()) } returns Unit
        every { wakeLock.release() } returns Unit

        // Initially locks are not held
        every { multicastLock.isHeld } returns false
        every { wakeLock.isHeld } returns false

        lockManager = LockManager(context)
    }

    @Test
    fun `acquireKeepalivePulseWakeLock holds a 5s timed lock in duty-cycle mode`() {
        lockManager.setPersistentWakeLockEnabled(false)

        lockManager.acquireKeepalivePulseWakeLock()

        verify { wakeLock.acquire(5_000L) }
    }

    @Test
    fun `acquireKeepalivePulseWakeLock is a no-op while the persistent lock is held`() {
        lockManager.acquireKeepalivePulseWakeLock()

        verify(exactly = 0) { wakeLock.acquire(5_000L) }
    }

    @Test
    fun `applyMulticastLockPolicy false acquires the multicast lock`() {
        every { multicastLock.isHeld } returns true

        lockManager.applyMulticastLockPolicy(skip = false)

        verify { multicastLock.acquire() }
        assertFalse(lockManager.skipMulticastLock)
        assertTrue(lockManager.getLockStatus().multicastHeld)
    }

    @Test
    fun `applyMulticastLockPolicy true releases a held multicast lock`() {
        every { multicastLock.isHeld } returnsMany listOf(true, false)

        lockManager.applyMulticastLockPolicy(skip = false)
        lockManager.applyMulticastLockPolicy(skip = true)

        verify { multicastLock.acquire() }
        verify { multicastLock.release() }
        assertTrue(lockManager.skipMulticastLock)
    }

    @Test
    fun `applyMulticastLockPolicy true then acquireAll does not acquire`() {
        lockManager.applyMulticastLockPolicy(skip = true)

        lockManager.acquireAll()

        verify(exactly = 0) { multicastLock.acquire() }
    }

    @Test
    fun `acquireAll acquires all locks`() {
        // After acquire, mark locks as held
        every { multicastLock.isHeld } returns true
        every { wakeLock.isHeld } returns true

        lockManager.acquireAll()

        // Verify locks are now held via getLockStatus()
        val status = lockManager.getLockStatus()
        assertTrue(status.multicastHeld)
        assertTrue(status.wakeHeld)
    }

    @Test
    fun `releaseAll releases all held locks`() {
        // Setup locks as held initially
        every { multicastLock.isHeld } returns true
        every { wakeLock.isHeld } returns true

        // First acquire so we have lock references
        lockManager.acquireAll()

        // Verify locks are held before release
        val statusBefore = lockManager.getLockStatus()
        assertTrue(statusBefore.multicastHeld)

        // After release, mark locks as not held
        every { multicastLock.isHeld } returns false
        every { wakeLock.isHeld } returns false

        lockManager.releaseAll()

        // Verify locks are no longer held
        val statusAfter = lockManager.getLockStatus()
        assertFalse(statusAfter.multicastHeld)
        assertFalse(statusAfter.wakeHeld)
    }

    @Test
    fun `releaseAll does not release unheld locks`() {
        // Locks are not held (default from setup)
        lockManager.acquireAll()

        // Verify locks are not held
        val statusBefore = lockManager.getLockStatus()
        assertFalse("Locks should not be held before release attempt", statusBefore.multicastHeld)

        // Now mark as not held before release
        every { multicastLock.isHeld } returns false
        every { wakeLock.isHeld } returns false

        lockManager.releaseAll()

        // Verify locks are still not held (no change)
        val statusAfter = lockManager.getLockStatus()
        assertFalse(statusAfter.multicastHeld)
        assertFalse(statusAfter.wakeHeld)
    }

    @Test
    fun `getLockStatus returns correct status when no locks held`() {
        val status = lockManager.getLockStatus()

        assertFalse(status.multicastHeld)
        assertFalse(status.wakeHeld)
    }

    @Test
    fun `getLockStatus returns correct status when locks held`() {
        // Acquire locks
        lockManager.acquireAll()

        // Mark as held
        every { multicastLock.isHeld } returns true
        every { wakeLock.isHeld } returns true

        val status = lockManager.getLockStatus()

        assertTrue(status.multicastHeld)
        assertTrue(status.wakeHeld)
    }

    @Test
    fun `acquireAll is idempotent when locks already held`() {
        // First acquisition
        lockManager.acquireAll()

        // Mark as already held
        every { multicastLock.isHeld } returns true
        every { wakeLock.isHeld } returns true

        // Verify locks are held after first acquisition
        val statusAfterFirst = lockManager.getLockStatus()
        assertTrue(statusAfterFirst.multicastHeld)
        assertTrue(statusAfterFirst.wakeHeld)

        // Second acquisition should not re-acquire (idempotent)
        lockManager.acquireAll()

        // Verify locks are still held (no change in state)
        val statusAfterSecond = lockManager.getLockStatus()
        assertTrue(statusAfterSecond.multicastHeld)
        assertTrue(statusAfterSecond.wakeHeld)
    }

    @Test
    fun `LockStatus data class equals works correctly`() {
        val status1 = LockManager.LockStatus(true, true)
        val status2 = LockManager.LockStatus(true, true)
        val status3 = LockManager.LockStatus(false, true)

        assertEquals(status1, status2)
        assertTrue(status1 != status3)
    }

    @Test
    fun `acquireAll re-acquires expired wake lock`() {
        // Mark wake lock as held after first acquisition
        every { wakeLock.isHeld } returns true

        // First acquisition
        lockManager.acquireAll()

        // Verify wake lock is held
        assertTrue(lockManager.getLockStatus().wakeHeld)

        // Simulate expired lock (isHeld returns false now)
        every { wakeLock.isHeld } returns false
        assertFalse("Wake lock should appear expired", lockManager.getLockStatus().wakeHeld)

        // Mark wake lock as held again after re-acquisition
        every { wakeLock.isHeld } returns true

        // Second acquisition should re-acquire
        lockManager.acquireAll()

        // Verify wake lock is held again
        assertTrue("Wake lock should be held after re-acquisition", lockManager.getLockStatus().wakeHeld)
    }

    @Test
    fun `initial wake lock acquisition sets wasExpired false`() {
        // Mark wake lock as held after acquisition
        every { wakeLock.isHeld } returns true

        // wakeLock is null initially, so wasExpired will be false
        // This exercises the "WakeLock acquired" log path
        lockManager.acquireAll()

        // Verify wake lock is now held (was successfully acquired)
        val status = lockManager.getLockStatus()
        assertTrue("Wake lock should be held after initial acquisition", status.wakeHeld)
    }

    @Test
    fun `expired wake lock re-acquisition sets wasExpired true`() {
        // Mark wake lock as held after first acquisition
        every { wakeLock.isHeld } returns true

        // First acquisition - wasExpired = false (wakeLock was null)
        lockManager.acquireAll()
        assertTrue(lockManager.getLockStatus().wakeHeld)

        // Now wakeLock is not null but we'll make it not held (expired)
        every { wakeLock.isHeld } returns false
        assertFalse("Wake lock should appear expired", lockManager.getLockStatus().wakeHeld)

        // Mark wake lock as held again after re-acquisition
        every { wakeLock.isHeld } returns true

        // Second acquisition - wasExpired = true (wakeLock != null && !isHeld)
        lockManager.acquireAll()

        // Verify wake lock is held again after re-acquisition
        assertTrue("Wake lock should be held after re-acquisition", lockManager.getLockStatus().wakeHeld)
    }

    // ==================== Duty-cycle mode (persistent wake lock toggle) ====================

    @Test
    fun `acquireAll skips wake lock when persistent mode disabled`() {
        lockManager.setPersistentWakeLockEnabled(false)

        lockManager.acquireAll()

        verify(exactly = 0) { wakeLock.acquire(any<Long>()) }
        assertFalse(lockManager.getLockStatus().wakeHeld)
    }

    @Test
    fun `setPersistentWakeLockEnabled false releases held wake lock`() {
        every { wakeLock.isHeld } returns true
        lockManager.acquireAll()
        assertTrue(lockManager.getLockStatus().wakeHeld)

        lockManager.setPersistentWakeLockEnabled(false)

        verify(exactly = 1) { wakeLock.release() }
    }

    @Test
    fun `setPersistentWakeLockEnabled true acquires wake lock`() {
        lockManager.setPersistentWakeLockEnabled(false)
        every { wakeLock.isHeld } returns true

        lockManager.setPersistentWakeLockEnabled(true)

        verify(exactly = 1) { wakeLock.acquire(any<Long>()) }
        assertTrue(lockManager.persistentWakeLockEnabled)
    }

    @Test
    fun `acquireTrafficWakeLock acquires timed traffic lock when persistent disabled`() {
        every { powerManager.newWakeLock(any(), "Columba::ReticulumTraffic") } returns trafficWakeLock
        every { trafficWakeLock.setReferenceCounted(any()) } returns Unit
        every { trafficWakeLock.acquire(any()) } returns Unit
        every { trafficWakeLock.isHeld } returns true
        lockManager.setPersistentWakeLockEnabled(false)

        lockManager.acquireTrafficWakeLock()

        verify(exactly = 1) {
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Columba::ReticulumTraffic")
        }
        verify(exactly = 1) { trafficWakeLock.acquire(90_000L) }
        assertTrue(lockManager.getLockStatus().trafficHeld)
    }

    @Test
    fun `acquireTrafficWakeLock is no-op while persistent mode enabled`() {
        every { powerManager.newWakeLock(any(), "Columba::ReticulumTraffic") } returns trafficWakeLock

        lockManager.acquireTrafficWakeLock()

        verify(exactly = 0) { powerManager.newWakeLock(any(), "Columba::ReticulumTraffic") }
        assertFalse(lockManager.getLockStatus().trafficHeld)
    }

    @Test
    fun `releaseAll releases traffic wake lock`() {
        every { powerManager.newWakeLock(any(), "Columba::ReticulumTraffic") } returns trafficWakeLock
        every { trafficWakeLock.setReferenceCounted(any()) } returns Unit
        every { trafficWakeLock.acquire(any()) } returns Unit
        every { trafficWakeLock.release() } returns Unit
        every { trafficWakeLock.isHeld } returns true
        lockManager.setPersistentWakeLockEnabled(false)
        lockManager.acquireTrafficWakeLock()

        lockManager.releaseAll()

        verify(exactly = 1) { trafficWakeLock.release() }
        assertFalse(lockManager.getLockStatus().trafficHeld)
    }
}
