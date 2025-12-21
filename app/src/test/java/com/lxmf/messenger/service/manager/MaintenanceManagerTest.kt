package com.lxmf.messenger.service.manager

import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for MaintenanceManager.
 *
 * Tests the periodic wake lock refresh mechanism that prevents the
 * 10-hour wake lock timeout from expiring during long-running sessions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MaintenanceManagerTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testScope: TestScope
    private lateinit var lockManager: LockManager
    private lateinit var maintenanceManager: MaintenanceManager

    @Before
    fun setup() {
        testScope = TestScope(testDispatcher)
        lockManager = mockk(relaxed = true)
        maintenanceManager = MaintenanceManager(lockManager, testScope)
    }

    @After
    fun tearDown() {
        maintenanceManager.stop()
        clearAllMocks()
    }

    @Test
    fun `start creates running maintenance job`() =
        runTest {
            maintenanceManager.start()
            testScope.runCurrent()

            assertTrue("Maintenance job should be running after start", maintenanceManager.isRunning())
        }

    @Test
    fun `stop cancels running maintenance job`() =
        runTest {
            maintenanceManager.start()
            testScope.runCurrent()

            maintenanceManager.stop()

            assertFalse("Maintenance job should not be running after stop", maintenanceManager.isRunning())
        }

    @Test
    fun `isRunning returns false when not started`() {
        assertFalse("Maintenance job should not be running initially", maintenanceManager.isRunning())
    }

    @Test
    fun `refreshLocks calls lockManager acquireAll`() {
        maintenanceManager.refreshLocks()

        verify(exactly = 1) { lockManager.acquireAll() }
    }

    @Test
    fun `refreshLocks handles exception gracefully`() {
        every { lockManager.acquireAll() } throws RuntimeException("Test error")

        // Should not throw
        maintenanceManager.refreshLocks()

        verify(exactly = 1) { lockManager.acquireAll() }
    }

    @Test
    fun `maintenance job refreshes locks after 9 hours`() =
        runTest {
            maintenanceManager.start()
            testScope.runCurrent()

            // Initially no refreshes (only initial acquisition before start)
            verify(exactly = 0) { lockManager.acquireAll() }

            // Advance time by 9 hours
            testScope.advanceTimeBy(MaintenanceManager.REFRESH_INTERVAL_MS)
            testScope.runCurrent()

            // Should have refreshed once
            verify(exactly = 1) { lockManager.acquireAll() }
        }

    @Test
    fun `maintenance job refreshes locks multiple times`() =
        runTest {
            maintenanceManager.start()
            testScope.runCurrent()

            // Advance time by 27 hours (3 refresh intervals)
            testScope.advanceTimeBy(3 * MaintenanceManager.REFRESH_INTERVAL_MS)
            testScope.runCurrent()

            // Should have refreshed 3 times
            verify(exactly = 3) { lockManager.acquireAll() }
        }

    @Test
    fun `maintenance job does not refresh before interval`() =
        runTest {
            maintenanceManager.start()
            testScope.runCurrent()

            // Advance time by 8 hours (less than 9 hour interval)
            testScope.advanceTimeBy(8 * 60 * 60 * 1000L)
            testScope.runCurrent()

            // Should not have refreshed yet
            verify(exactly = 0) { lockManager.acquireAll() }
        }

    @Test
    fun `maintenance job stops refreshing after stop`() =
        runTest {
            maintenanceManager.start()
            testScope.runCurrent()

            // Advance 9 hours - first refresh
            testScope.advanceTimeBy(MaintenanceManager.REFRESH_INTERVAL_MS)
            testScope.runCurrent()
            verify(exactly = 1) { lockManager.acquireAll() }

            // Stop the job
            maintenanceManager.stop()

            // Advance another 9 hours
            testScope.advanceTimeBy(MaintenanceManager.REFRESH_INTERVAL_MS)
            testScope.runCurrent()

            // Should still only have 1 refresh (no more after stop)
            verify(exactly = 1) { lockManager.acquireAll() }
        }

    @Test
    fun `starting again cancels previous job`() =
        runTest {
            maintenanceManager.start()
            testScope.runCurrent()
            assertTrue(maintenanceManager.isRunning())

            // Start again
            maintenanceManager.start()
            testScope.runCurrent()

            // Should still be running (new job)
            assertTrue(maintenanceManager.isRunning())
        }

    @Test
    fun `stop is safe to call when not running`() {
        assertFalse(maintenanceManager.isRunning())

        // Should not throw
        maintenanceManager.stop()

        assertFalse(maintenanceManager.isRunning())
    }

    @Test
    fun `stop is safe to call multiple times`() =
        runTest {
            maintenanceManager.start()
            testScope.runCurrent()

            maintenanceManager.stop()
            maintenanceManager.stop()
            maintenanceManager.stop()

            assertFalse(maintenanceManager.isRunning())
        }

    @Test
    fun `isRunning returns true when job is active`() =
        runTest {
            maintenanceManager.start()
            testScope.runCurrent()

            assertTrue("isRunning should return true when job is active", maintenanceManager.isRunning())

            maintenanceManager.stop()
        }

    @Test
    fun `isRunning returns false after stop`() =
        runTest {
            maintenanceManager.start()
            testScope.runCurrent()
            assertTrue(maintenanceManager.isRunning())

            maintenanceManager.stop()

            assertFalse("isRunning should return false after stop", maintenanceManager.isRunning())
        }

    @Test
    fun `start when already running replaces job`() =
        runTest {
            // First start
            maintenanceManager.start()
            testScope.runCurrent()
            assertTrue(maintenanceManager.isRunning())

            // Second start should work without error
            maintenanceManager.start()
            testScope.runCurrent()

            // Should still be running
            assertTrue(maintenanceManager.isRunning())

            maintenanceManager.stop()
        }
}
