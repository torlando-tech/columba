package com.lxmf.messenger.service.manager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for wrapper state race condition patterns used in PythonWrapperManager.
 *
 * The pattern under test:
 * 1. Check if wrapper exists
 * 2. If exists, shutdown existing wrapper
 * 3. Create new wrapper
 * 4. Assign new wrapper to state
 *
 * Race condition: Between check and assign, another thread could modify the wrapper.
 *
 * This test class extracts the pattern and tests it in isolation using a mock wrapper.
 */
class WrapperStateRaceTest {
    /**
     * Mock wrapper that tracks creation/shutdown for testing.
     */
    class MockWrapper(val id: Int) {
        var isShutdown = false
            private set

        fun shutdown() {
            isShutdown = true
        }
    }

    /**
     * Simulates UNSAFE wrapper state management (current pattern).
     */
    class UnsafeWrapperManager {
        var wrapper: MockWrapper? = null
        private var nextId = AtomicInteger(0)

        suspend fun initialize(): MockWrapper {
            // Check-then-act race condition
            if (wrapper != null) {
                shutdownExisting()
            }

            // Simulate some initialization work
            delay(5)

            val newWrapper = MockWrapper(nextId.incrementAndGet())
            wrapper = newWrapper
            return newWrapper
        }

        private fun shutdownExisting() {
            wrapper?.shutdown()
            wrapper = null
        }

        fun shutdown() {
            wrapper?.shutdown()
            wrapper = null
        }

        fun <T> withWrapper(block: (MockWrapper) -> T): T? {
            val w = wrapper ?: return null
            return block(w)
        }
    }

    /**
     * Simulates SAFE wrapper state management with Mutex.
     */
    class SafeWrapperManager {
        private val mutex = Mutex()
        var wrapper: MockWrapper? = null
            private set
        private var nextId = AtomicInteger(0)

        suspend fun initialize(): MockWrapper {
            return mutex.withLock {
                // Shutdown existing while holding lock
                wrapper?.shutdown()
                wrapper = null

                // Simulate some initialization work (can release lock here for real work)
                delay(5)

                val newWrapper = MockWrapper(nextId.incrementAndGet())
                wrapper = newWrapper
                newWrapper
            }
        }

        suspend fun shutdown() {
            val wrapperToShutdown: MockWrapper?
            mutex.withLock {
                wrapperToShutdown = wrapper
                wrapper = null
            }
            // Shutdown outside lock to avoid blocking
            wrapperToShutdown?.shutdown()
        }

        suspend fun <T> withWrapper(block: (MockWrapper) -> T): T? {
            mutex.withLock {
                val w = wrapper ?: return null
                return block(w)
            }
        }
    }

    /**
     * Test: Unsafe pattern can result in leaked wrappers when concurrent
     * initialize calls occur.
     */
    @Test
    fun `unsafe pattern can leak wrappers during concurrent initialize`() =
        runBlocking {
            val manager = UnsafeWrapperManager()
            val createdWrappers = mutableListOf<MockWrapper>()
            val mutex = Mutex()

            // Launch multiple concurrent initialize calls
            val jobs =
                List(10) {
                    async(Dispatchers.Default) {
                        val wrapper = manager.initialize()
                        mutex.withLock {
                            createdWrappers.add(wrapper)
                        }
                    }
                }

            jobs.awaitAll()

            // Count how many wrappers weren't properly shut down
            val notShutdown = createdWrappers.count { !it.isShutdown }

            // Only one wrapper should remain not shutdown (the final one)
            // With unsafe code, multiple might remain not shutdown (leaked)
            println("Wrappers not shutdown (unsafe): $notShutdown/${createdWrappers.size}")

            // The final wrapper should be the one in the manager
            val finalWrapper = manager.wrapper
            assertNotNull("Manager should have a wrapper", finalWrapper)
        }

    /**
     * Test: Safe pattern properly shuts down all but the final wrapper.
     */
    @Test
    fun `safe pattern properly manages wrapper lifecycle`() =
        runBlocking {
            val manager = SafeWrapperManager()
            val createdWrappers = mutableListOf<MockWrapper>()
            val mutex = Mutex()

            // Launch multiple concurrent initialize calls
            val jobs =
                List(10) {
                    async(Dispatchers.Default) {
                        val wrapper = manager.initialize()
                        mutex.withLock {
                            createdWrappers.add(wrapper)
                        }
                    }
                }

            jobs.awaitAll()

            // Count how many wrappers weren't properly shut down
            val notShutdown = createdWrappers.count { !it.isShutdown }

            // Exactly one wrapper should remain not shutdown (the final one)
            assertEquals(
                "Exactly one wrapper should remain (the current one)",
                1,
                notShutdown,
            )

            // The final wrapper should be the one in the manager
            val finalWrapper = manager.wrapper
            assertNotNull("Manager should have a wrapper", finalWrapper)
            assertTrue("Final wrapper should not be shutdown", !finalWrapper!!.isShutdown)
        }

    /**
     * Test: withWrapper during shutdown returns null safely.
     */
    @Test
    fun `safe pattern withWrapper during shutdown returns null`() =
        runBlocking {
            val manager = SafeWrapperManager()
            manager.initialize()

            val results = mutableListOf<Int?>()
            val mutex = Mutex()

            // Concurrent shutdown and withWrapper calls
            val shutdownJob =
                launch(Dispatchers.Default) {
                    delay(5)
                    manager.shutdown()
                }

            val readJobs =
                List(20) {
                    async(Dispatchers.Default) {
                        repeat(10) {
                            val result = manager.withWrapper { w -> w.id }
                            mutex.withLock {
                                results.add(result)
                            }
                        }
                    }
                }

            shutdownJob.join()
            readJobs.awaitAll()

            // Results should be either valid IDs or null, never exceptions
            results.forEach { result ->
                assertTrue(
                    "Result should be valid ID or null",
                    result == null || result > 0,
                )
            }
        }

    /**
     * Test: Rapid initialize-shutdown cycles don't corrupt state.
     */
    @Test
    fun `safe pattern handles rapid init-shutdown cycles`() =
        runBlocking {
            val manager = SafeWrapperManager()

            repeat(20) {
                val wrapper = manager.initialize()
                assertNotNull("Wrapper should be created", wrapper)

                // Small delay
                delay(2)

                manager.shutdown()
                assertNull("Wrapper should be null after shutdown", manager.wrapper)
            }
        }

    /**
     * Test: State is consistent after concurrent operations.
     */
    @Test
    fun `safe pattern maintains state consistency`() =
        runBlocking {
            val manager = SafeWrapperManager()
            val inconsistencies = AtomicInteger(0)

            // Writers: initialize and shutdown
            val writerJobs =
                List(5) { writerIndex ->
                    launch(Dispatchers.Default) {
                        repeat(10) {
                            if (it % 2 == 0) {
                                manager.initialize()
                            } else {
                                manager.shutdown()
                            }
                            delay(1)
                        }
                    }
                }

            // Readers: check state consistency
            val readerJobs =
                List(10) {
                    launch(Dispatchers.IO) {
                        repeat(50) {
                            manager.withWrapper { wrapper ->
                                // If we got a wrapper, it should not be shutdown
                                if (wrapper.isShutdown) {
                                    inconsistencies.incrementAndGet()
                                }
                            }
                        }
                    }
                }

            writerJobs.forEach { it.join() }
            readerJobs.forEach { it.join() }

            assertEquals(
                "Should find no inconsistencies (shutdown wrapper visible)",
                0,
                inconsistencies.get(),
            )
        }

    /**
     * Test: Generation tracking prevents stale operations.
     */
    @Test
    fun `generation tracking prevents stale updates`() =
        runBlocking {
            // This tests the pattern where we track generations to prevent
            // old operations from corrupting state
            var currentGeneration = 0
            var wrapper: MockWrapper? = null
            val mutex = Mutex()

            suspend fun initializeWithGeneration(): MockWrapper {
                mutex.withLock {
                    currentGeneration++
                    val myGeneration = currentGeneration

                    // Shutdown existing
                    wrapper?.shutdown()

                    // Simulate slow initialization
                    delay(10)

                    // Check if we're still current generation
                    check(currentGeneration == myGeneration) { "Stale initialization" }

                    val newWrapper = MockWrapper(myGeneration)
                    wrapper = newWrapper
                    return newWrapper
                }
            }

            // Only one initialization should succeed when called concurrently
            val results =
                List(5) {
                    async(Dispatchers.Default) {
                        try {
                            initializeWithGeneration()
                            true
                        } catch (e: IllegalStateException) {
                            println("Stale initialization detected: ${e.message}")
                            false
                        }
                    }
                }

            val successes = results.awaitAll().count { it }

            // With mutex, all should succeed (serialized)
            assertEquals(
                "All initializations should succeed when serialized",
                5,
                successes,
            )
        }
}
