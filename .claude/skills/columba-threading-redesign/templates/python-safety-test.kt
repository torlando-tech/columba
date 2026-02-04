// Python Threading Safety Test Template

package com.lxmf.messenger.threading

import com.chaquo.python.PyObject
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*
import kotlin.system.measureTimeMillis

class PythonThreadSafetyTest {
    
    @Test
    fun testConcurrentPythonCalls() = runTest {
        val results = (1..10).map { i ->
            async(Dispatchers.IO) {
                PythonExecutor.execute("test_$i") {
                    wrapper.callAttr("method", i)
                }
            }
        }.awaitAll()
        
        assertEquals(10, results.size)
    }
    
    @Test
    fun testRapidPythonInvocations() = runTest {
        repeat(1000) { i ->
            PythonExecutor.execute("rapid_$i") {
                wrapper.callAttr("simple_method", i)
            }
        }
        // Should complete without errors
    }
    
    @Test
    fun testPythonTimeout() = runTest {
        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking {
                PythonExecutor.execute("timeout_test", timeoutMs = 100) {
                    Thread.sleep(1000)  // Simulate hang
                    wrapper.callAttr("method")
                }
            }
        }
    }
}
