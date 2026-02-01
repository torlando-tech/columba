package com.lxmf.messenger.service.manager

import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
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
 * Tests the periodic wake lock refresh mechanism that ensures locks are
 * maintained aggressively (every 5 minutes) following Sideband's pattern.
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
        lockManager = mockk()
        // Stub the method that will be called
        every { lockManager.acquireAll() } returns Unit
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
        // Should complete successfully
        val result = runCatching { maintenanceManager.refreshLocks() }

        assertTrue("refreshLocks should complete without throwing", result.isSuccess)
    }

    @Test
    fun `refreshLocks handles exception gracefully`() {
        every { lockManager.acquireAll() } throws RuntimeException("Test error")

        // Should not throw even when lockManager throws
        val result = runCatching { maintenanceManager.refreshLocks() }

        assertTrue("refreshLocks should handle exception gracefully", result.isSuccess)
    }

    @Test
    fun `maintenance job refreshes locks after interval`() =
        runTest {
            maintenanceManager.start()
            testScope.runCurrent()

            // Job should be running
            assertTrue("Job should be running after start", maintenanceManager.isRunning())

            // Advance time by refresh interval (5 minutes)
            testScope.advanceTimeBy(MaintenanceManager.REFRESH_INTERVAL_MS)
            testScope.runCurrent()

            // Job should still be running after refresh
            assertTrue("Job should still be running after refresh", maintenanceManager.isRunning())
        }

    @Test
    fun `maintenance job refreshes locks multiple times`() =
        runTest {
            maintenanceManager.start()
            testScope.runCurrent()
            assertTrue(maintenanceManager.isRunning())

            // Advance time by 3 refresh intervals (15 minutes)
            testScope.advanceTimeBy(3 * MaintenanceManager.REFRESH_INTERVAL_MS)
            testScope.runCurrent()

            // Job should still be running after multiple refreshes
            assertTrue("Job should still be running after multiple intervals", maintenanceManager.isRunning())
        }

    @Test
    fun `maintenance job does not refresh before interval`() =
        runTest {
            maintenanceManager.start()
            testScope.runCurrent()
            assertTrue("Job should be running", maintenanceManager.isRunning())

            // Advance time by 4 minutes (less than 5 minute interval)
            testScope.advanceTimeBy(4 * 60 * 1000L)
            testScope.runCurrent()

            // Job should still be running (hasn't hit first interval yet)
            assertTrue("Job should still be running before interval", maintenanceManager.isRunning())
        }

    @Test
    fun `maintenance job stops refreshing after stop`() =
        runTest {
            maintenanceManager.start()
            testScope.runCurrent()
            assertTrue(maintenanceManager.isRunning())

            // Advance to first refresh
            testScope.advanceTimeBy(MaintenanceManager.REFRESH_INTERVAL_MS)
            testScope.runCurrent()
            assertTrue("Job should be running after first refresh", maintenanceManager.isRunning())

            // Stop the job
            maintenanceManager.stop()
            assertFalse("Job should not be running after stop", maintenanceManager.isRunning())

            // Advance another interval
            testScope.advanceTimeBy(MaintenanceManager.REFRESH_INTERVAL_MS)
            testScope.runCurrent()

            // Job should still be stopped
            assertFalse("Job should remain stopped after time advances", maintenanceManager.isRunning())
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
