# Chaquopy Testing Reference

## Overview

This reference provides comprehensive patterns for testing Kotlin-Python integration using Chaquopy in Android apps.

## Critical Constraints

### Python Requires Device/Emulator

**Chaquopy Python cannot run in JVM-based unit tests.** All Python integration tests MUST be instrumented tests (`app/src/androidTest/`).

```
❌ app/src/test/ (unit tests)
   - Python.start() will fail
   - No Android runtime available

✅ app/src/androidTest/ (instrumented tests)
   - Runs on emulator/device
   - Full Android runtime
   - Python works correctly
```

## Python Initialization Pattern

### Standard Pattern

```kotlin
@RunWith(AndroidJUnit4::class)
class PythonTest {
    private lateinit var python: Python
    private lateinit var wrapper: PyObject

    @Before
    fun setup() {
        // Get instrumentation context
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Start Python (only once per test run)
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }

        // Get Python instance
        python = Python.getInstance()

        // Import module and create wrapper
        val module = python.getModule("module_name")
        wrapper = module.callAttr("ClassName", constructor_args)
    }
}
```

### Singleton Python Wrapper Pattern

If Python wrapper should be shared across tests:

```kotlin
object PythonTestHelper {
    private var _wrapper: PyObject? = null

    fun getWrapper(context: Context): PyObject {
        if (_wrapper == null) {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context))
            }

            val module = Python.getInstance().getModule("module_name")
            _wrapper = module.callAttr("ClassName", args)
        }
        return _wrapper!!
    }
}

// In test
@Before
fun setup() {
    wrapper = PythonTestHelper.getWrapper(context)
}
```

## Type Conversion Testing

### String Conversion

```kotlin
@Test
fun testStringConversion() {
    // Kotlin → Python str
    val kotlinString = "test"
    val result = wrapper.callAttr("echo_string", kotlinString)

    // Python str → Kotlin String
    val backToKotlin = result.toString()
    assertEquals(kotlinString, backToKotlin)
}
```

### Integer Conversion

```kotlin
@Test
fun testIntConversion() {
    // Kotlin Int → Python int
    val kotlinInt = 42
    val result = wrapper.callAttr("echo_int", kotlinInt)

    // Python int → Kotlin Int
    val backToKotlin = result.toInt()
    assertEquals(kotlinInt, backToKotlin)
}
```

### ByteArray Conversion

```kotlin
@Test
fun testByteArrayConversion() {
    // Kotlin ByteArray → Python bytes
    val kotlinBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
    val result = wrapper.callAttr("echo_bytes", kotlinBytes)

    // Python bytes → Kotlin ByteArray
    val backToKotlin = result.toJava(ByteArray::class.java)
    assertArrayEquals(kotlinBytes, backToKotlin)
}
```

### List Conversion

```kotlin
@Test
fun testListConversion() {
    // Kotlin List → Python list
    val kotlinList = listOf("a", "b", "c")
    val result = wrapper.callAttr("echo_list", kotlinList)

    // Python list → Kotlin List
    val backToKotlin = result.asList()
    assertEquals(3, backToKotlin.size)
    assertEquals("a", backToKotlin[0].toString())
}
```

### Map Conversion

```kotlin
@Test
fun testMapConversion() {
    // Kotlin Map → Python dict
    val kotlinMap = mapOf("key1" to "value1", "key2" to "value2")
    val result = wrapper.callAttr("echo_dict", kotlinMap)

    // Python dict → Kotlin Map
    val backToKotlin = result.asMap()
    assertEquals(2, backToKotlin.size)
    assertEquals("value1", backToKotlin["key1"].toString())
}
```

## Exception Handling

### Catching Python Exceptions

```kotlin
@Test
fun testPythonException() {
    val exception = assertThrows<PyException> {
        wrapper.callAttr("method_that_raises_error")
    }

    // PyException contains Python traceback
    assertTrue(exception.message?.contains("Python error message") == true)
}
```

### Testing Error Propagation

```kotlin
@Test
fun testErrorHandling() = runTest {
    // Test that Python errors are properly handled in Kotlin
    val result = try {
        withContext(Dispatchers.IO) {
            wrapper.callAttr("risky_operation")
        }
        Result.success(Unit)
    } catch (e: PyException) {
        Result.failure(e)
    }

    assertTrue(result.isFailure)
}
```

## Threading and GIL Testing

### Testing Concurrent Access

```kotlin
@Test
fun testConcurrentPythonAccess() = runBlocking {
    // Multiple Kotlin threads calling Python
    val results = (1..10).map { i ->
        async(Dispatchers.IO) {
            wrapper.callAttr("method", i)
        }
    }.awaitAll()

    // All should succeed (GIL provides safety)
    assertEquals(10, results.size)
}
```

### Testing GIL Release During I/O

```kotlin
@Test
fun testGILReleaseDuringIO() = runBlocking {
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

    // Should complete in ~500ms (parallel), not 1500ms (sequential)
    assertTrue(timeMs < 1000, "GIL not released: ${timeMs}ms")
}
```

## Memory Management

### Testing PyObject Cleanup

```kotlin
@Test
fun testPyObjectMemory() {
    var pyObject: PyObject? = null

    try {
        pyObject = wrapper.callAttr("create_large_object")
        // Use object
        assertNotNull(pyObject)
    } finally {
        // Always close PyObject to prevent memory leaks
        pyObject?.close()
    }
}
```

## Performance Measurement

### Measuring Call Overhead

```kotlin
@Test
fun measurePythonCallOverhead() {
    val iterations = 1000

    val totalTime = measureTimeMillis {
        repeat(iterations) {
            wrapper.callAttr("noop") // Minimal Python method
        }
    }

    val avgTime = totalTime.toDouble() / iterations
    println("Average call overhead: ${"%.3f".format(avgTime)}ms")

    // Log for analysis (typical: 1-5ms depending on device)
}
```

## Common Patterns

### Testing Initialization

```kotlin
@Test
fun testPythonInitialization() {
    val result = wrapper.callAttr("initialize", configJson)

    assertTrue(result.toBoolean(), "Initialization failed")
}
```

### Testing Status Methods

```kotlin
@Test
fun testGetStatus() {
    val status = wrapper.callAttr("get_status").toString()

    assertTrue(status in listOf("ready", "initializing", "shutdown"))
}
```

### Testing Async Operations

```kotlin
@Test
fun testAsyncPythonOperation() = runBlocking {
    val result = withContext(Dispatchers.IO) {
        wrapper.callAttr("long_operation")
    }

    assertNotNull(result)
}
```

## Test Data Setup

### Creating Test Storage Directory

```kotlin
@Before
fun setupStorage() {
    val testDir = context.getDir("python_test", Context.MODE_PRIVATE)
    testStoragePath = testDir.absolutePath

    // Clean up from previous tests
    testDir.listFiles()?.forEach { it.deleteRecursively() }
}

@After
fun cleanupStorage() {
    File(testStoragePath).deleteRecursively()
}
```

## Debugging Python Tests

### Enabling Python Logging

```python
# In your Python module
import logging
logging.basicConfig(level=logging.DEBUG)
```

### Checking Python State in Tests

```kotlin
@Test
fun debugPythonState() {
    // Get Python sys module
    val sys = python.getModule("sys")

    // Check Python version
    val version = sys["version"].toString()
    println("Python version: $version")

    // Check module path
    val path = sys["path"].asList()
    println("Python path: $path")
}
```

---

*Reference based on Chaquopy documentation and real-world testing patterns*
