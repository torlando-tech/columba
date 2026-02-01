@file:Suppress("InjectDispatcher")
package com.lxmf.messenger.reticulum.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread safety tests for SmartPoller.
 *
 * Tests concurrent access patterns to verify SmartPoller is safe
 * when called from multiple coroutines/threads simultaneously.
 *
 * Part of Phase 2.2 integration testing.
 */
class SmartPollerThreadSafetyTest {
    private lateinit var poller: SmartPoller

    @Before
    fun setup() {
        poller = SmartPoller(minInterval = 2_000, maxInterval = 30_000)
    }

    @Test
    fun `concurrent markActive calls are safe`() =
        runTest {
            val callCount = AtomicInteger(0)
            val jobs =
                List(100) {
                    launch(Dispatchers.Default) {
                        poller.markActive()
                        callCount.incrementAndGet()
                    }
                }

            // Wait for all coroutines to complete
            jobs.forEach { it.join() }

            // Verify all calls completed
            assertEquals(100, callCount.get())

            // Verify poller is in correct state
            assertEquals(2_000L, poller.getCurrentInterval())
        }

    @Test
    fun `concurrent markIdle calls are safe`() =
        runTest {
            val callCount = AtomicInteger(0)
            val jobs =
                List(100) {
                    launch(Dispatchers.Default) {
                        poller.markIdle()
                        callCount.incrementAndGet()
                    }
                }

            // Wait for all coroutines to complete
            jobs.forEach { it.join() }

            // Verify all calls completed
            assertEquals(100, callCount.get())

            // No assertion on interval since idle doesn't change interval until getNextInterval()
        }

    @Test
    fun `concurrent getNextInterval calls are safe`() =
        runTest {
            poller.markIdle() // Start in idle state

            val intervals = mutableListOf<Long>()
            val mutex = Mutex()

            val jobs =
                List(50) {
                    launch(Dispatchers.Default) {
                        val interval = poller.getNextInterval()
                        mutex.withLock {
                            intervals.add(interval)
                        }
                    }
                }

            // Wait for all coroutines to complete
            jobs.forEach { it.join() }

            // Verify all calls completed
            assertEquals(50, intervals.size)

            // Verify intervals are within valid range
            intervals.forEach { interval ->
                assertTrue(
                    "Interval $interval should be between 2000 and 30000",
                    interval in 2_000L..30_000L,
                )
            }

            // Verify we're getting exponential backoff (at least some intervals should be > minInterval)
            val hasBackoff = intervals.any { it > 2_000L }
            assertTrue("Should have some backoff intervals", hasBackoff)
        }

    @Test
    fun `rapid active-idle toggling works correctly`() =
        runTest {
            val iterations = 1_000
            var completedIterations = 0

            repeat(iterations) {
                poller.markActive()
                poller.markIdle()
                completedIterations++
            }

            // Verify all toggles completed
            assertEquals(iterations, completedIterations)

            // Verify poller is in a valid state
            val interval = poller.getCurrentInterval()
            assertTrue("Interval should be valid: $interval", interval >= 2_000L)
        }

    @Test
    fun `concurrent access from multiple dispatchers is safe`() =
        runTest {
            val results = mutableListOf<String>()
            val mutex = Mutex()

            val jobs =
                listOf(
                    // Dispatcher.IO
                    launch(Dispatchers.IO) {
                        repeat(50) {
                            poller.markActive()
                            val interval = poller.getNextInterval()
                            mutex.withLock {
                                results.add("IO:$interval")
                            }
                        }
                    },
                    // Dispatcher.Default
                    launch(Dispatchers.Default) {
                        repeat(50) {
                            poller.markIdle()
                            val interval = poller.getNextInterval()
                            mutex.withLock {
                                results.add("Default:$interval")
                            }
                        }
                    },
                    // Dispatcher.Unconfined
                    launch(Dispatchers.Unconfined) {
                        repeat(50) {
                            poller.reset()
                            val interval = poller.getCurrentInterval()
                            mutex.withLock {
                                results.add("Unconfined:$interval")
                            }
                        }
                    },
                )

            // Wait for all coroutines to complete
            jobs.forEach { it.join() }

            // Verify all operations completed (50 + 50 + 50 = 150)
            assertEquals(150, results.size)

            // Verify no exceptions occurred and all dispatchers completed
            val ioCount = results.count { it.startsWith("IO:") }
            val defaultCount = results.count { it.startsWith("Default:") }
            val unconfinedCount = results.count { it.startsWith("Unconfined:") }

            assertEquals(50, ioCount)
            assertEquals(50, defaultCount)
            assertEquals(50, unconfinedCount)
        }

    @Test
    fun `concurrent reset calls are safe`() =
        runTest {
            // Put poller in advanced state
            poller.markIdle()
            repeat(5) { poller.getNextInterval() }
            assertTrue(poller.getCurrentInterval() > 2_000L)

            val callCount = AtomicInteger(0)
            val jobs =
                List(100) {
                    launch(Dispatchers.Default) {
                        poller.reset()
                        callCount.incrementAndGet()
                    }
                }

            // Wait for all coroutines to complete
            jobs.forEach { it.join() }

            // Verify all calls completed
            assertEquals(100, callCount.get())

            // Verify poller is reset to initial state
            assertEquals(2_000L, poller.getCurrentInterval())
        }

    @Test
    fun `getCurrentInterval does not interfere with concurrent state changes`() =
        runTest {
            val readCount = AtomicInteger(0)
            val writeCount = AtomicInteger(0)

            val jobs =
                listOf(
                    // Readers
                    launch(Dispatchers.Default) {
                        repeat(200) {
                            poller.getCurrentInterval()
                            readCount.incrementAndGet()
                        }
                    },
                    // Writers
                    launch(Dispatchers.IO) {
                        repeat(100) {
                            poller.markActive()
                            writeCount.incrementAndGet()
                            yield() // Give readers a chance
                        }
                    },
                    launch(Dispatchers.IO) {
                        repeat(100) {
                            poller.markIdle()
                            writeCount.incrementAndGet()
                            yield() // Give readers a chance
                        }
                    },
                )

            // Wait for all coroutines to complete
            jobs.forEach { it.join() }

            // Verify all operations completed
            assertEquals(200, readCount.get())
            assertEquals(200, writeCount.get())

            // Verify poller is in valid state
            val interval = poller.getCurrentInterval()
            assertTrue("Interval should be valid: $interval", interval in 2_000L..30_000L)
        }

    @Test
    fun `stress test with mixed operations`() =
        runTest {
            val operationCount = AtomicInteger(0)
            val errors = mutableListOf<Throwable>()
            val errorMutex = Mutex()

            val jobs =
                List(20) { index ->
                    launch(Dispatchers.Default) {
                        try {
                            repeat(100) {
                                when (index % 5) {
                                    0 -> poller.markActive()
                                    1 -> poller.markIdle()
                                    2 -> poller.getNextInterval()
                                    3 -> poller.reset()
                                    4 -> poller.getCurrentInterval()
                                }
                                operationCount.incrementAndGet()
                            }
                        } catch (e: Throwable) {
                            errorMutex.withLock {
                                errors.add(e)
                            }
                        }
                    }
                }

            // Wait for all coroutines to complete
            jobs.forEach { it.join() }

            // Verify no errors occurred
            if (errors.isNotEmpty()) {
                fail("Errors occurred during stress test: ${errors.map { it.message }}")
            }

            // Verify all operations completed (20 coroutines * 100 operations = 2000)
            assertEquals(2_000, operationCount.get())

            // Verify poller is in valid state
            val interval = poller.getCurrentInterval()
            assertTrue("Interval should be valid: $interval", interval in 2_000L..30_000L)
        }
}
