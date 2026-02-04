// Python Performance Testing Template
// Copy this template for measuring Python call performance

package com.lxmf.messenger.python

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.PyObject
import com.chaquo.python.android.AndroidPlatform
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis
import kotlin.test.assertTrue

/**
 * Performance tests for Python integration.
 * Measures:
 * - Python call overhead
 * - GIL contention
 * - Type conversion overhead
 */
@RunWith(AndroidJUnit4::class)
class PythonPerformanceTest {
    
    private lateinit var wrapper: PyObject
    
    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
        
        wrapper = Python.getInstance()
            .getModule("your_module")
            .callAttr("YourClass")
    }
    
    @Test
    fun measureAveragePythonCallTime() {
        val iterations = 1000
        var totalTime = 0L
        
        // Warm up (first calls may be slower)
        repeat(10) {
            wrapper.callAttr("simple_method", it)
        }
        
        // Measure
        repeat(iterations) { i ->
            val time = measureTimeMillis {
                wrapper.callAttr("simple_method", i)
            }
            totalTime += time
        }
        
        val avgTime = totalTime.toDouble() / iterations
        println("Average Python call time: ${"%.2f".format(avgTime)}ms")
        
        // Target: < 10ms average
        assertTrue(
            avgTime < 10.0,
            "Average Python call time exceeds target: ${"%.2f".format(avgTime)}ms > 10ms"
        )
    }
    
    @Test
    fun measurePythonInitializationTime() {
        // Measure time to initialize Python wrapper
        val timeMs = measureTimeMillis {
            val module = Python.getInstance().getModule("your_module")
            val instance = module.callAttr("YourClass", "test_path")
            instance.callAttr("initialize", "{}")
        }
        
        println("Python initialization time: ${timeMs}ms")
        
        // Target: < 3000ms (3 seconds)
        assertTrue(
            timeMs < 3000,
            "Initialization time exceeds target: ${timeMs}ms > 3000ms"
        )
    }
    
    @Test
    fun measureTypeConversionOverhead() {
        val largeByteArray = ByteArray(10_000) { it.toByte() }
        
        val timeMs = measureTimeMillis {
            repeat(100) {
                wrapper.callAttr("process_bytes", largeByteArray)
            }
        }
        
        val avgTime = timeMs.toDouble() / 100
        println("Average type conversion time: ${"%.2f".format(avgTime)}ms")
        
        // Should be reasonable even for large data
        assertTrue(avgTime < 50.0, "Type conversion too slow: ${"%.2f".format(avgTime)}ms")
    }
}
