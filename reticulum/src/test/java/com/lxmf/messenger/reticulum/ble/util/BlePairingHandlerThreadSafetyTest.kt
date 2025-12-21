package com.lxmf.messenger.reticulum.ble.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import io.mockk.clearAllMocks
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread safety tests for BlePairingHandler register/unregister operations.
 *
 * Tests verify that concurrent calls to register() and unregister() don't cause
 * race conditions that could lead to double-registration or missed unregistration.
 */
class BlePairingHandlerThreadSafetyTest {
    private lateinit var mockContext: Context
    private val registerCallCount = AtomicInteger(0)

    @Before
    fun setup() {
        registerCallCount.set(0)
        mockContext = mockk<Context>(relaxed = true)
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `concurrent register calls only register receiver once`() {
        val handler = BlePairingHandler(mockContext)
        val threadCount = 10
        val startLatch = CountDownLatch(1) // Ensures all threads start together
        val doneLatch = CountDownLatch(threadCount)

        // Prepare threads to call register simultaneously
        repeat(threadCount) {
            Thread {
                startLatch.await() // Wait for signal to start
                handler.register()
                doneLatch.countDown()
            }.start()
        }

        // Signal all threads to start at once
        startLatch.countDown()

        assertTrue("All threads should complete", doneLatch.await(5, TimeUnit.SECONDS))

        // Count total calls to registerReceiver (either overload)
        // In unit tests, Build.VERSION.SDK_INT is typically 0, so 2-param version is called
        // Use verify to ensure exactly 1 call was made
        verify(exactly = 1) {
            mockContext.registerReceiver(
                any<BroadcastReceiver>(),
                any<IntentFilter>(),
            )
        }
    }

    @Test
    fun `concurrent unregister calls only unregister receiver once`() {
        val handler = BlePairingHandler(mockContext)
        handler.register() // First register

        val threadCount = 10
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)

        // Prepare threads to call unregister simultaneously
        repeat(threadCount) {
            Thread {
                startLatch.await()
                handler.unregister()
                doneLatch.countDown()
            }.start()
        }

        // Signal all threads to start at once
        startLatch.countDown()

        assertTrue("All threads should complete", doneLatch.await(5, TimeUnit.SECONDS))

        // Verify unregisterReceiver was called exactly once
        verify(exactly = 1) { mockContext.unregisterReceiver(any()) }
    }

    @Test
    fun `interleaved register and unregister calls complete without deadlock`() {
        val handler = BlePairingHandler(mockContext)
        val iterations = 50
        val latch = CountDownLatch(iterations * 2)

        // Interleave register and unregister from multiple threads
        repeat(iterations) {
            Thread {
                handler.register()
                latch.countDown()
            }.start()
            Thread {
                handler.unregister()
                latch.countDown()
            }.start()
        }

        assertTrue(
            "All operations should complete without deadlock",
            latch.await(10, TimeUnit.SECONDS),
        )
    }

    @Test
    fun `rapid register unregister cycles are thread safe`() {
        val handler = BlePairingHandler(mockContext)
        val cycles = 20
        val latch = CountDownLatch(cycles)

        // Each thread does a register-unregister cycle
        repeat(cycles) {
            Thread {
                handler.register()
                Thread.sleep(1) // Small delay to increase contention
                handler.unregister()
                latch.countDown()
            }.start()
        }

        assertTrue(
            "All cycles should complete without errors",
            latch.await(10, TimeUnit.SECONDS),
        )
    }
}
