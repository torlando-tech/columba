// Python Integration Testing Template
// Copy this template for testing Chaquopy Python integration

package com.lxmf.messenger.python

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.PyException
import com.chaquo.python.PyObject
import com.chaquo.python.android.AndroidPlatform
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith

/**
 * IMPORTANT: Python tests MUST be instrumented tests (androidTest).
 * Chaquopy Python cannot run in JVM-based unit tests.
 */
@RunWith(AndroidJUnit4::class)
class PythonIntegrationTest {
    
    private lateinit var context: Context
    private lateinit var python: Python
    private lateinit var wrapper: PyObject
    
    @Before
    fun setup() {
        // Get instrumentation context
        context = InstrumentationRegistry.getInstrumentation().targetContext
        
        // Initialize Python (only once per test run)
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
        
        // Get Python instance
        python = Python.getInstance()
        
        // Import your Python module and create instance
        val module = python.getModule("your_module_name")
        wrapper = module.callAttr("YourClassName", "constructor_args")
    }
    
    @Test
    fun testPythonMethodReturnsString() {
        // Call Python method
        val result = wrapper.callAttr("your_method", "test_arg")
        
        // Convert Python str to Kotlin String
        val kotlinString = result.toString()
        
        // Assert
        assertNotNull(kotlinString)
        assertEquals("expected_value", kotlinString)
    }
    
    @Test
    fun testPythonMethodReturnsInt() {
        // Call Python method that returns int
        val result = wrapper.callAttr("your_int_method", 42)
        
        // Convert Python int to Kotlin Int
        val kotlinInt = result.toInt()
        
        // Assert
        assertEquals(42, kotlinInt)
    }
    
    @Test
    fun testPythonMethodWithByteArray() {
        // Prepare ByteArray (Kotlin → Python bytes)
        val kotlinBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        
        // Pass to Python
        val result = wrapper.callAttr("process_bytes", kotlinBytes)
        
        // Get back as ByteArray
        val resultBytes = result.toJava(ByteArray::class.java)
        
        // Assert
        assertEquals(kotlinBytes.size, resultBytes.size)
    }
    
    @Test
    fun testPythonMethodWithList() {
        // Prepare List (Kotlin → Python list)
        val kotlinList = listOf("a", "b", "c")
        
        // Pass to Python
        val result = wrapper.callAttr("process_list", kotlinList)
        
        // Get back as List
        val resultList = result.asList()
        
        // Assert
        assertEquals(3, resultList.size)
    }
    
    @Test
    fun testPythonExceptionHandling() {
        // Test that Python exceptions are properly propagated as PyException
        assertFailsWith<PyException> {
            wrapper.callAttr("method_that_raises_exception")
        }
    }
    
    @Test
    fun testPythonBooleanReturn() {
        // Call method that returns bool
        val result = wrapper.callAttr("is_valid")
        
        // Convert to Boolean
        val kotlinBoolean = result.toBoolean()
        
        // Assert
        assertEquals(true, kotlinBoolean)
    }
}
