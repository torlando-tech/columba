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
 * Unit tests for HealthCheckManager.
 *
 * Tests the Python heartbeat monitoring mechanism that detects when the
 * Python process is hung and triggers a service restart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HealthCheckManagerTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testScope: TestScope
    private lateinit var wrapperManager: PythonWrapperManager
    private lateinit var healthCheckManager: HealthCheckManager
    private var staleHeartbeatCallCount = 0

    @Before
    fun setup() {
        testScope = TestScope(testDispatcher)
        wrapperManager = mockk(relaxed = true)
        staleHeartbeatCallCount = 0
        healthCheckManager = HealthCheckManager(
            wrapperManager = wrapperManager,
            scope = testScope,
            onStaleHeartbeat = { staleHeartbeatCallCount++ },
        )
    }

    @After
    fun tearDown() {
        healthCheckManager.stop()
        clearAllMocks()
    }

    @Test
    fun `start creates running health check job`() =
        runTest {
            healthCheckManager.start()
            testScope.runCurrent()

            assertTrue("Health check job should be running after start", healthCheckManager.isRunning())
        }

    @Test
    fun `stop cancels running health check job`() =
        runTest {
            healthCheckManager.start()
            testScope.runCurrent()

            healthCheckManager.stop()

            assertFalse("Health check job should not be running after stop", healthCheckManager.isRunning())
        }

    @Test
    fun `isRunning returns false when not started`() {
        assertFalse("Health check job should not be running initially", healthCheckManager.isRunning())
    }

    @Test
    fun `healthy heartbeat does not trigger callback`() =
        runTest {
            // Mock a fresh heartbeat (current time)
            val currentTime = System.currentTimeMillis() / 1000.0
            every { wrapperManager.getHeartbeat() } returns currentTime

            healthCheckManager.start()
            testScope.runCurrent()

            // Advance past initial delay and first check
            testScope.advanceTimeBy(HealthCheckManager.CHECK_INTERVAL_MS + 1000)
            testScope.runCurrent()

            // Should not trigger callback for healthy heartbeat
            assertTrue("Stale callback should not be called for healthy heartbeat", staleHeartbeatCallCount == 0)
        }

    @Test
    fun `stale heartbeat triggers callback after consecutive checks`() =
        runTest {
            // Mock a stale heartbeat (20 seconds old)
            val staleTime = (System.currentTimeMillis() / 1000.0) - 20.0
            every { wrapperManager.getHeartbeat() } returns staleTime

            healthCheckManager.start()
            testScope.runCurrent()

            // Advance past initial delay
            testScope.advanceTimeBy(HealthCheckManager.CHECK_INTERVAL_MS)
            testScope.runCurrent()

            // First stale check - should not trigger yet (need 2 consecutive)
            assertTrue("Callback should not trigger on first stale check", staleHeartbeatCallCount == 0)

            // Advance to second check
            testScope.advanceTimeBy(HealthCheckManager.CHECK_INTERVAL_MS)
            testScope.runCurrent()

            // Second consecutive stale check - should trigger
            assertTrue("Callback should trigger after 2 consecutive stale checks", staleHeartbeatCallCount == 1)
        }

    @Test
    fun `zero heartbeat does not trigger callback`() =
        runTest {
            // Mock zero heartbeat (wrapper not initialized)
            every { wrapperManager.getHeartbeat() } returns 0.0

            healthCheckManager.start()
            testScope.runCurrent()

            // Advance through several check intervals
            testScope.advanceTimeBy(HealthCheckManager.CHECK_INTERVAL_MS * 5)
            testScope.runCurrent()

            // Should not trigger callback for zero heartbeat
            assertTrue("Callback should not be called for zero heartbeat", staleHeartbeatCallCount == 0)
        }

    @Test
    fun `recovered heartbeat resets stale count`() =
        runTest {
            // Start with stale heartbeat
            val staleTime = (System.currentTimeMillis() / 1000.0) - 20.0
            every { wrapperManager.getHeartbeat() } returns staleTime

            healthCheckManager.start()
            testScope.runCurrent()

            // First stale check
            testScope.advanceTimeBy(HealthCheckManager.CHECK_INTERVAL_MS)
            testScope.runCurrent()

            // Now heartbeat recovers
            val freshTime = System.currentTimeMillis() / 1000.0
            every { wrapperManager.getHeartbeat() } returns freshTime

            // Second check - heartbeat is fresh now
            testScope.advanceTimeBy(HealthCheckManager.CHECK_INTERVAL_MS)
            testScope.runCurrent()

            // Should not have triggered callback (recovery reset the count)
            assertTrue("Callback should not trigger when heartbeat recovers", staleHeartbeatCallCount == 0)
        }

    @Test
    fun `stop is safe to call when not running`() {
        assertFalse(healthCheckManager.isRunning())

        // Should not throw
        healthCheckManager.stop()

        assertFalse(healthCheckManager.isRunning())
    }

    @Test
    fun `stop is safe to call multiple times`() =
        runTest {
            healthCheckManager.start()
            testScope.runCurrent()

            healthCheckManager.stop()
            healthCheckManager.stop()
            healthCheckManager.stop()

            assertFalse(healthCheckManager.isRunning())
        }

    @Test
    fun `start when already running replaces job`() =
        runTest {
            healthCheckManager.start()
            testScope.runCurrent()
            assertTrue(healthCheckManager.isRunning())

            // Start again should work without error
            healthCheckManager.start()
            testScope.runCurrent()

            assertTrue(healthCheckManager.isRunning())
        }

    @Test
    fun `exception in getHeartbeat does not crash check`() =
        runTest {
            every { wrapperManager.getHeartbeat() } throws RuntimeException("Test error")

            healthCheckManager.start()
            testScope.runCurrent()

            // Advance through check - should not crash
            testScope.advanceTimeBy(HealthCheckManager.CHECK_INTERVAL_MS * 2)
            testScope.runCurrent()

            // Job should still be running
            assertTrue(healthCheckManager.isRunning())
            // Callback should not be triggered on exception
            assertTrue("Callback should not trigger on exception", staleHeartbeatCallCount == 0)
        }

    @Test
    fun `stale threshold is 10 seconds`() =
        runTest {
            // Mock heartbeat that's just past the threshold (10.1 seconds old)
            // Code uses > comparison, so exactly 10 seconds is not stale
            val thresholdTime = (System.currentTimeMillis() / 1000.0) - 10.1
            every { wrapperManager.getHeartbeat() } returns thresholdTime

            healthCheckManager.start()
            testScope.runCurrent()

            // Advance through two checks
            testScope.advanceTimeBy(HealthCheckManager.CHECK_INTERVAL_MS * 2 + 1000)
            testScope.runCurrent()

            // Should trigger when past 10 second threshold
            assertTrue("Callback should trigger past 10 second threshold", staleHeartbeatCallCount >= 1)
        }

    @Test
    fun `check interval is 5 seconds`() {
        assertTrue(
            "Check interval should be 5 seconds",
            HealthCheckManager.CHECK_INTERVAL_MS == 5_000L,
        )
    }

    @Test
    fun `stale threshold is 10 seconds constant`() {
        assertTrue(
            "Stale threshold should be 10 seconds",
            HealthCheckManager.STALE_THRESHOLD_MS == 10_000L,
        )
    }
}
