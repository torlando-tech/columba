// Python Threading Safety Testing Template
// Copy this template for testing Python GIL behavior and threading safety

package com.lxmf.messenger.python

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.PyObject
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests to verify Python threading safety with Chaquopy.
 * These tests verify:
 * 1. Multiple Kotlin threads can call Python safely (GIL protects)
 * 2. GIL is released during I/O operations
 * 3. No race conditions or deadlocks
 */
@RunWith(AndroidJUnit4::class)
class PythonThreadingSafetyTest {
    
    private lateinit var context: Context
    private lateinit var python: Python
    private lateinit var wrapper: PyObject
    
    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
        
        python = Python.getInstance()
        wrapper = python.getModule("your_module").callAttr("YourClass")
    }
    
    @Test
    fun testConcurrentPythonAccess() = runBlocking {
        // Launch 10 concurrent Python calls from different Kotlin threads
        val results = (1..10).map { i ->
            async(Dispatchers.IO) {
                wrapper.callAttr("echo", i)
            }
        }.awaitAll()
        
        // All calls should complete successfully
        assertEquals(10, results.size)
        results.forEachIndexed { index, result ->
            assertEquals(index + 1, result.toInt())
        }
    }
    
    @Test
    fun testRapidPythonInvocations() = runBlocking {
        // Stress test: 1000 rapid Python calls
        val iterations = 1000
        var successCount = 0
        
        val timeMs = measureTimeMillis {
            repeat(iterations) { i ->
                val result = wrapper.callAttr("simple_method", i).toInt()
                assertEquals(i, result)
                successCount++
            }
        }
        
        // Verify all completed
        assertEquals(iterations, successCount)
        
        // Measure average time per call
        val avgTimePerCall = timeMs.toDouble() / iterations
        println("Average Python call time: ${"%.2f".format(avgTimePerCall)}ms")
        
        // Should be < 10ms average
        assertTrue(avgTimePerCall < 10.0, "Average call time too high: $avgTimePerCall ms")
    }
    
    @Test
    fun testLongRunningPythonOperationReleasesGIL() = runBlocking {
        // Test that GIL is released during I/O operations
        val sleepDuration = 0.5 // 500ms
        
        // Launch 3 concurrent sleep operations
        val timeMs = measureTimeMillis {
            val jobs = (1..3).map {
                async(Dispatchers.IO) {
                    wrapper.callAttr("sleep", sleepDuration)
                }
            }
            jobs.awaitAll()
        }
        
        // If GIL is properly released during sleep, total time should be ~500ms
        // If GIL blocks, total time would be 1500ms (3 * 500ms)
        println("Time for 3 concurrent sleeps: ${timeMs}ms")
        
        // Allow some overhead, but should be much less than sequential execution
        assertTrue(
            timeMs < 1000,
            "Operations appear to have blocked each other (GIL not released): ${timeMs}ms"
        )
    }
    
    @Test
    fun testPythonCallsFromMultipleDispatchers() = runBlocking {
        // Test calling Python from different Kotlin dispatchers
        val ioResult = async(Dispatchers.IO) {
            wrapper.callAttr("echo", "from_io")
        }
        
        val defaultResult = async(Dispatchers.Default) {
            wrapper.callAttr("echo", "from_default")
        }
        
        // Both should complete successfully
        assertEquals("from_io", ioResult.await().toString())
        assertEquals("from_default", defaultResult.await().toString())
    }
    
    @Test
    fun testMixedConcurrentOperations() = runBlocking {
        // Mix of fast and slow operations running concurrently
        val timeMs = measureTimeMillis {
            val jobs = listOf(
                async(Dispatchers.IO) { wrapper.callAttr("fast_op") },
                async(Dispatchers.IO) { wrapper.callAttr("sleep", 0.1) },
                async(Dispatchers.IO) { wrapper.callAttr("fast_op") },
                async(Dispatchers.IO) { wrapper.callAttr("sleep", 0.1) },
                async(Dispatchers.IO) { wrapper.callAttr("fast_op") }
            )
            jobs.awaitAll()
        }
        
        // Fast operations shouldn't be blocked by slow ones
        println("Mixed operations completed in: ${timeMs}ms")
        assertTrue(timeMs < 500, "Fast operations were blocked: ${timeMs}ms")
    }
}
