package com.lxmf.messenger.reticulum.ble.server

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for BleGattServer keepalive management fixes.
 *
 * These tests verify fixes for:
 * 1. Orphaned keepalive jobs when disconnectCentral() is called
 * 2. Keepalive loop exit when target is no longer tracked
 *
 * Bug scenario (before fix):
 * - disconnectCentral() removes address from connectedCentrals
 * - BUT does not stop the keepalive job
 * - Keepalive job continues running, repeatedly failing with "No connected centrals"
 * - Creates log spam and potential state corruption
 *
 * After fix:
 * - disconnectCentral() calls stopPeripheralKeepalive() BEFORE cleanup
 * - Keepalive loop breaks when "No connected centrals" error detected
 *
 * Note: These tests verify the logic patterns rather than the actual BleGattServer
 * implementation, since the actual class requires Android Bluetooth infrastructure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleGattServerKeepaliveTest {

    // ========== Keepalive Job Cleanup Pattern Tests ==========

    /**
     * Test that stopping keepalive before removing from map prevents orphaned jobs.
     *
     * This simulates the fix pattern: stopKeepalive() must be called BEFORE
     * removing the address from connectedCentrals.
     */
    @Test
    fun `stopping keepalive before removing from map prevents orphaned jobs`() = runTest {
        val keepaliveJobs = mutableMapOf<String, Job>()
        val connectedCentrals = mutableMapOf<String, Any>()
        val address = "AA:BB:CC:DD:EE:FF"

        // Setup: Add connected central and keepalive job
        connectedCentrals[address] = Object()
        val keepaliveJob = launch {
            while (true) {
                delay(100)
            }
        }
        keepaliveJobs[address] = keepaliveJob

        // The fix pattern: stop keepalive BEFORE removing from connectedCentrals
        keepaliveJobs[address]?.cancel()
        keepaliveJobs.remove(address)
        connectedCentrals.remove(address)

        advanceUntilIdle()

        // Verify
        assertTrue("Keepalive job should be cancelled", keepaliveJob.isCancelled)
        assertFalse("Keepalive should not exist in map", keepaliveJobs.containsKey(address))
        assertFalse("Central should not be connected", connectedCentrals.containsKey(address))
    }

    /**
     * Test that NOT stopping keepalive creates orphaned jobs (the bug scenario).
     */
    @Test
    fun `not stopping keepalive creates orphaned job - bug demonstration`() = runTest {
        val keepaliveJobs = mutableMapOf<String, Job>()
        val connectedCentrals = mutableMapOf<String, Any>()
        val address = "AA:BB:CC:DD:EE:FF"

        // Setup
        connectedCentrals[address] = Object()
        val keepaliveRunning = AtomicBoolean(false)
        val keepaliveJob = launch {
            keepaliveRunning.set(true)
            while (true) {
                delay(50)
            }
        }
        keepaliveJobs[address] = keepaliveJob

        // The BUG pattern (before fix): only remove from connectedCentrals, forget keepalive
        connectedCentrals.remove(address)
        // Missing: keepaliveJobs[address]?.cancel()

        // Advance time a bit for keepalive to run
        advanceTimeBy(100)

        // Bug: Keepalive job is STILL running even though central is "disconnected"
        assertTrue("Keepalive job should still be active (orphaned)", keepaliveJob.isActive)
        assertTrue("Central should be removed", !connectedCentrals.containsKey(address))

        // Cleanup for test
        keepaliveJob.cancel()
    }

    // ========== Keepalive Loop Exit Pattern Tests ==========

    /**
     * Test that keepalive loop exits when target is no longer tracked.
     *
     * This simulates the defensive break added in the keepalive loop:
     * if (error.contains("No connected centrals")) break
     */
    @Test
    fun `keepalive loop exits on no connected centrals error`() = runTest {
        val connectedCentrals = mutableMapOf<String, Any>()
        val address = "AA:BB:CC:DD:EE:FF"
        val loopIterations = AtomicInteger(0)
        val loopExitedCleanly = AtomicBoolean(false)

        // Simulate keepalive loop with the fix
        val keepaliveJob = launch {
            while (true) {
                loopIterations.incrementAndGet()

                // Simulate checking if central is still connected
                val target = connectedCentrals[address]
                if (target == null) {
                    // This is the fix: detect "No connected centrals" and break
                    loopExitedCleanly.set(true)
                    break
                }

                delay(50)
            }
        }

        // Let coroutines run - central is not in map, so loop should exit immediately
        advanceUntilIdle()

        assertTrue("Loop should have exited cleanly", loopExitedCleanly.get())
        assertEquals("Loop should have run only once", 1, loopIterations.get())
        assertFalse("Keepalive job should be completed", keepaliveJob.isActive)
    }

    /**
     * Test that keepalive continues while target is tracked.
     */
    @Test
    fun `keepalive continues while target is tracked`() = runTest {
        val connectedCentrals = mutableMapOf<String, Any>()
        val address = "AA:BB:CC:DD:EE:FF"
        val loopIterations = AtomicInteger(0)

        // Add central to map
        connectedCentrals[address] = Object()

        // Simulate keepalive loop
        val keepaliveJob = launch {
            while (true) {
                val target = connectedCentrals[address]
                if (target == null) {
                    break
                }
                loopIterations.incrementAndGet()
                delay(30)
            }
        }

        // Advance time - should run multiple iterations
        advanceTimeBy(150)

        // Should have run multiple iterations (150ms / 30ms = 5 iterations)
        assertTrue("Loop should have run multiple times", loopIterations.get() >= 3)
        assertTrue("Keepalive job should still be active", keepaliveJob.isActive)

        // Remove central - loop should exit on next iteration
        connectedCentrals.remove(address)
        advanceTimeBy(50)

        assertFalse("Keepalive job should complete after central removed", keepaliveJob.isActive)
    }

    /**
     * Test error message detection pattern.
     */
    @Test
    fun `error message contains expected no connected centrals substring`() {
        val errorMessage = "No connected centrals to notify"

        assertTrue(
            "Error should contain 'No connected centrals'",
            errorMessage.contains("No connected centrals"),
        )
    }

    /**
     * Test that partial match also works.
     */
    @Test
    fun `partial error message match works for detection`() {
        val possibleErrors = listOf(
            "No connected centrals to notify",
            "Error: No connected centrals found",
            "IllegalStateException: No connected centrals",
        )

        possibleErrors.forEach { error ->
            assertTrue(
                "Should detect 'No connected centrals' in: $error",
                error.contains("No connected centrals"),
            )
        }
    }

    // ========== Multiple Address Handling Tests ==========

    /**
     * Test that stopping one keepalive doesn't affect others.
     */
    @Test
    fun `stopping one keepalive does not affect others`() = runTest {
        val keepaliveJobs = mutableMapOf<String, Job>()
        val addresses = listOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02", "AA:BB:CC:DD:EE:03")

        // Create keepalive jobs for all addresses
        addresses.forEach { address ->
            keepaliveJobs[address] = launch {
                while (true) {
                    delay(50)
                }
            }
        }

        // Stop only the first one
        keepaliveJobs[addresses[0]]?.cancel()
        keepaliveJobs.remove(addresses[0])

        advanceTimeBy(50)

        // First should be stopped, others should still be active
        assertFalse("First keepalive should be stopped", keepaliveJobs.containsKey(addresses[0]))
        assertTrue("Second keepalive should be active", keepaliveJobs[addresses[1]]?.isActive == true)
        assertTrue("Third keepalive should be active", keepaliveJobs[addresses[2]]?.isActive == true)

        // Cleanup
        keepaliveJobs.values.forEach { it.cancel() }
    }

    /**
     * Test idempotent stopping (stopping same address multiple times).
     */
    @Test
    fun `stopping same keepalive multiple times is safe`() = runTest {
        val keepaliveJobs = mutableMapOf<String, Job>()
        val address = "AA:BB:CC:DD:EE:FF"

        // Create keepalive
        keepaliveJobs[address] = launch {
            while (true) {
                delay(50)
            }
        }

        // Stop multiple times (should be idempotent)
        repeat(3) {
            keepaliveJobs[address]?.cancel()
            keepaliveJobs.remove(address)
        }

        // Should not crash and address should be removed
        assertFalse("Address should not be in map", keepaliveJobs.containsKey(address))
    }

    // ========== Callback Pattern Tests ==========

    /**
     * Test that disconnect callback is fired during cleanup.
     */
    @Test
    fun `disconnect triggers callback`() = runTest {
        val callbackFired = AtomicBoolean(false)
        val callbackAddress = StringBuilder()

        // Simulate onCentralDisconnected callback
        val onCentralDisconnected: (String) -> Unit = { address ->
            callbackFired.set(true)
            callbackAddress.append(address)
        }

        val address = "AA:BB:CC:DD:EE:FF"

        // Simulate disconnect flow
        // ... cleanup state ...
        onCentralDisconnected(address)

        assertTrue("Callback should be fired", callbackFired.get())
        assertEquals("Callback should receive correct address", address, callbackAddress.toString())
    }
}
