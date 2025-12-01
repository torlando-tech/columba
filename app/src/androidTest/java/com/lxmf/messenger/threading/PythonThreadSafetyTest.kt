package com.lxmf.messenger.threading

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

/**
 * Threading Safety Tests for Python/Chaquopy Integration
 *
 * Phase 1, Task 1.1: Verify Python Threading Safety
 *
 * These tests verify that:
 * 1. Multiple Kotlin coroutines can safely call Python methods concurrently
 * 2. Python's GIL (Global Interpreter Lock) provides adequate thread safety
 * 3. Chaquopy correctly handles multi-threaded access from Kotlin
 * 4. Long-running Python operations don't block other threads
 * 5. Different dispatchers can all safely call Python
 *
 * Success Criteria:
 * - All tests pass without exceptions
 * - 1000+ rapid invocations complete successfully
 * - No deadlocks or race conditions observed
 * - Long operations don't block concurrent access
 */
@RunWith(AndroidJUnit4::class)
class PythonThreadSafetyTest {
    private lateinit var wrapper: com.chaquo.python.PyObject

    @Before
    fun setup() {
        // Initialize Python if not already initialized
        if (!Python.isStarted()) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            Python.start(AndroidPlatform(context))
        }

        // Get Python instance and wrapper
        val py = Python.getInstance()
        val module = py.getModule("reticulum_wrapper")
        val storagePath = InstrumentationRegistry.getInstrumentation().targetContext.filesDir.absolutePath + "/test_reticulum"
        wrapper = module.callAttr("ReticulumWrapper", storagePath)
    }

    /**
     * Test 1: Concurrent Python Access
     *
     * Launches 10+ coroutines that simultaneously call Python methods.
     * Verifies that all calls complete successfully without exceptions.
     *
     * Thread-Safety Verification:
     * - Python GIL serializes bytecode execution
     * - Chaquopy handles coroutine → Python thread mapping
     * - No explicit Kotlin-side locking needed
     */
    @Test
    fun testConcurrentPythonAccess() =
        runBlocking {
            val concurrentCalls = 10
            val results = mutableListOf<Deferred<String>>()

            // Launch 10 concurrent coroutines calling Python
            repeat(concurrentCalls) { i ->
                results.add(
                    async(Dispatchers.IO) {
                        val message = "test_$i"
                        wrapper.callAttr("echo", message).toString()
                    },
                )
            }

            // Wait for all results
            val allResults = results.awaitAll()

            // Verify all calls succeeded
            assertEquals(concurrentCalls, allResults.size)

            // Verify each result is correct
            allResults.forEachIndexed { index, result ->
                assertEquals("test_$index", result)
            }

            println("✅ testConcurrentPythonAccess: $concurrentCalls concurrent calls completed successfully")
        }

    /**
     * Test 2: Rapid Python Invocations (Stress Test)
     *
     * Makes 1000 rapid calls to Python to stress-test the integration.
     * Verifies no crashes, deadlocks, or memory issues occur.
     *
     * Success Criteria:
     * - All 1000 calls complete without exception
     * - No memory leaks or resource exhaustion
     * - Performance remains consistent
     */
    @Test
    fun testRapidPythonInvocations() =
        runBlocking {
            val iterations = 1000
            var successCount = 0

            val timeMs =
                measureTimeMillis {
                    repeat(iterations) { i ->
                        try {
                            val result = wrapper.callAttr("simple_method", i).toInt()
                            assertEquals(i, result)
                            successCount++
                        } catch (e: Exception) {
                            fail("Python call $i failed: ${e.message}")
                        }
                    }
                }

            assertEquals(iterations, successCount)
            val avgTimePerCall = timeMs.toDouble() / iterations

            println("✅ testRapidPythonInvocations: $iterations calls in ${timeMs}ms (avg ${avgTimePerCall}ms/call)")

            // Sanity check: average call should be reasonable (< 10ms on modern hardware)
            assertTrue("Average call time too high: $avgTimePerCall ms", avgTimePerCall < 10.0)
        }

    /**
     * Test 3: Long-Running Python Operations
     *
     * Tests that long-running Python operations (e.g., sleep) don't block
     * other threads from calling Python. Python's GIL is released during
     * I/O operations like time.sleep().
     *
     * Thread-Safety Verification:
     * - GIL is released during sleep (C-level operation)
     * - Other threads can execute Python code while one sleeps
     * - No deadlock when multiple long operations run concurrently
     */
    @Test
    fun testLongRunningPythonOperation() =
        runBlocking {
            val sleepDuration = 0.5 // 500ms

            // Launch 3 concurrent long-running operations
            val timeMs =
                measureTimeMillis {
                    val jobs =
                        (1..3).map {
                            async(Dispatchers.IO) {
                                wrapper.callAttr("sleep", sleepDuration)
                            }
                        }
                    jobs.awaitAll()
                }

            // If they ran in parallel, total time should be ~500ms, not 1500ms
            // Allow some overhead (< 800ms indicates parallel execution)
            assertTrue(
                "Long operations appear to have blocked each other (${timeMs}ms total)",
                timeMs < 800,
            )

            println("✅ testLongRunningPythonOperation: 3 x 500ms sleeps completed in ${timeMs}ms (parallel execution confirmed)")
        }

    /**
     * Test 4: Python Calls from Multiple Dispatchers
     *
     * Verifies Python calls work correctly from different coroutine dispatchers:
     * - Dispatchers.IO (thread pool)
     * - Dispatchers.Default (computation)
     * - Dispatchers.Main (if applicable)
     *
     * Thread-Safety Verification:
     * - Chaquopy works correctly with all dispatcher types
     * - Thread pool transitions don't cause issues
     * - No dispatcher-specific crashes or deadlocks
     */
    @Test
    fun testPythonFromMultipleDispatchers() =
        runBlocking {
            val results = mutableListOf<Deferred<Pair<String, String>>>()

            // Call from Dispatchers.IO
            results.add(
                async(Dispatchers.IO) {
                    val result = wrapper.callAttr("echo", "from_IO").toString()
                    Pair("Dispatchers.IO", result)
                },
            )

            // Call from Dispatchers.Default
            results.add(
                async(Dispatchers.Default) {
                    val result = wrapper.callAttr("echo", "from_Default").toString()
                    Pair("Dispatchers.Default", result)
                },
            )

            // Call from Dispatchers.Unconfined
            results.add(
                async(Dispatchers.Unconfined) {
                    val result = wrapper.callAttr("echo", "from_Unconfined").toString()
                    Pair("Dispatchers.Unconfined", result)
                },
            )

            // Collect all results
            val allResults = results.awaitAll()

            // Verify each dispatcher's call succeeded
            assertEquals(3, allResults.size)
            allResults.forEach { (dispatcher, result) ->
                assertTrue("Result from $dispatcher is incorrect", result.startsWith("from_"))
                println("  ✓ $dispatcher: $result")
            }

            println("✅ testPythonFromMultipleDispatchers: All dispatchers can safely call Python")
        }

    /**
     * Test 5: Concurrent Calls with Different Operations
     *
     * Mix of different Python operations running concurrently to simulate
     * real-world usage patterns.
     */
    @Test
    fun testMixedConcurrentOperations() =
        runBlocking {
            val jobs = mutableListOf<Deferred<String>>()

            // Mix of echo, simple_method, and short sleeps
            repeat(20) { i ->
                jobs.add(
                    async(Dispatchers.IO) {
                        when (i % 3) {
                            0 -> {
                                wrapper.callAttr("echo", "test_$i").toString()
                                "echo"
                            }
                            1 -> {
                                wrapper.callAttr("simple_method", i).toInt()
                                "simple_method"
                            }
                            else -> {
                                wrapper.callAttr("sleep", 0.01) // 10ms sleep
                                "sleep"
                            }
                        }
                    },
                )
            }

            val results = jobs.awaitAll()

            assertEquals(20, results.size)
            println("✅ testMixedConcurrentOperations: 20 mixed operations completed successfully")
        }
}
