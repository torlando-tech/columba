# Chaquopy Limitations and Workarounds

## Overview

This document catalogs known Chaquopy limitations discovered through real-world usage and provides workarounds for common issues.

## Callback Registration Limitations

### Issue: Callbacks May Not Work Reliably

**Discovered in**: `reticulum_wrapper.py:306-329`

```python
# NOTE: Handler registration doesn't work reliably in Chaquopy due to threading limitations
# We use poll_received_announces() as a workaround to directly poll RNS.Transport.path_table
print("PYTHON: Registering announce handler (may not work in Chaquopy)")
RNS.Transport.register_announce_handler(self._announce_handler)
```

**Problem**: Python callbacks registered with external libraries may not execute reliably when called from daemon threads or different threading contexts.

**Workaround**: Use polling instead of callbacks
```python
def poll_received_announces(self) -> list:
    """Workaround: Directly poll instead of using callbacks"""
    announces = []
    # Directly access the data structure instead of relying on callbacks
    for destination_hash in RNS.Transport.path_table:
        announces.append(self._process_announce(destination_hash))
    return announces
```

**Testing Impact**: When testing callback-based features, verify behavior with both callback and polling approaches.

## Threading Constraints

### Python Requires Main Looper (Sometimes)

**Issue**: Some Android-specific Python operations may require main thread Looper.

**Symptom**: `RuntimeException: Can't create handler inside thread that has not called Looper.prepare()`

**Workaround**: For operations that require Looper, post to main thread:
```kotlin
withContext(Dispatchers.Main) {
    wrapper.callAttr("operation_needing_looper")
}
```

**However**: Most operations don't need this. Test first before assuming it's required.

### GIL Behavior with Native Extensions

**Issue**: Python C extensions may not release GIL during operations

**Impact**: Long-running C extension calls block all Python execution

**Testing**: Measure concurrent operation timing:
```kotlin
@Test
fun testOperationReleasesGIL() = runBlocking {
    val timeMs = measureTimeMillis {
        val jobs = (1..3).map {
            async(Dispatchers.IO) {
                wrapper.callAttr("potentially_blocking_operation")
            }
        }
        jobs.awaitAll()
    }

    // If time ~ 3x single operation, GIL not released
    // If time ~ 1x single operation, GIL released properly
}
```

## Resource Management

### PyObject Memory Leaks

**Issue**: PyObject references not released can cause memory leaks

**Solution**: Always close PyObjects when done
```kotlin
fun usePythonData() {
    var data: PyObject? = null
    try {
        data = wrapper.callAttr("get_data")
        processData(data)
    } finally {
        data?.close() // Important!
    }
}
```

**Testing**: Monitor memory over extended test runs
```kotlin
@Test
fun testNoMemoryLeaks() = runTest {
    val initialMemory = Runtime.getRuntime().totalMemory()

    repeat(1000) {
        val data = wrapper.callAttr("get_data")
        data.close()
    }

    System.gc()
    val finalMemory = Runtime.getRuntime().totalMemory()

    // Memory should be stable
    val memoryGrowth = finalMemory - initialMemory
    assertTrue(memoryGrowth < 10_000_000, "Memory leak detected: ${memoryGrowth / 1_000_000}MB")
}
```

## Type Conversion Limitations

### Complex Objects Don't Auto-Convert

**Issue**: Custom classes don't automatically convert between Kotlin and Python

**Workaround**: Use JSON or primitive types
```kotlin
// ❌ Won't work
data class CustomObject(val field: String)
wrapper.callAttr("method", CustomObject("value"))

// ✅ Works
val json = Json.encodeToString(CustomObject("value"))
wrapper.callAttr("method", json)
```

### Nullable Types

**Issue**: Python `None` conversion can be tricky

**Pattern**:
```kotlin
val result = wrapper.callAttr("might_return_none")

val value: String? = if (result == null || result.toString() == "None") {
    null
} else {
    result.toString()
}
```

## Testing Workarounds

### Testing Code That Requires Callbacks

**If callbacks don't work**, test the polling alternative:

```kotlin
@Test
fun testAnnouncesPolling() = runTest {
    // Instead of testing callback
    // Test polling method
    val announces = wrapper.callAttr("poll_received_announces").asList()

    assertNotNull(announces)
    // Verify polling returns expected data
}
```

### Testing Daemon Thread Behavior

**Issue**: Python daemon threads may not behave as expected

**Workaround**: Test observable effects, not thread state
```kotlin
@Test
fun testDaemonThreadEffect() {
    // Don't test thread state directly
    // Test the observable outcome

    wrapper.callAttr("start_daemon_thread")

    // Wait for effect
    Thread.sleep(1000)

    // Verify outcome
    val result = wrapper.callAttr("get_daemon_result")
    assertNotNull(result)
}
```

## Performance Considerations

### Slow First Call

**Issue**: First Python call after initialization is slower

**Workaround**: Warm up in setup
```kotlin
@Before
fun setup() {
    // ... initialize Python ...

    // Warm up (first call is slow)
    wrapper.callAttr("noop")
}
```

### JNI Overhead

**Issue**: Each Python call has JNI overhead (~1-5ms)

**Workaround**: Batch operations when possible
```kotlin
// ❌ Slow: Many individual calls
repeat(1000) {
    wrapper.callAttr("process_item", it)
}

// ✅ Fast: One batched call
wrapper.callAttr("process_items", (0..1000).toList())
```

## Known Issues and Workarounds

### Issue: "Python not started"

**Symptom**: `IllegalStateException: Python is not started`

**Cause**: Forgot to call `Python.start()` or trying to use Python in unit test

**Solution**:
1. Ensure `Python.start()` called in `@Before`
2. Verify test is in `androidTest`, not `test`

### Issue: Module Not Found

**Symptom**: `PyException: ModuleNotFoundError`

**Cause**: Python module not in correct directory or not in `pyc` configuration

**Solution**: Verify module in `src/main/python/` and included in build

### Issue: AttributeError from Python

**Symptom**: `PyException: AttributeError: 'Module' object has no attribute 'method'`

**Cause**: Method name typo or Python code issue

**Solution**:
1. Check spelling
2. Verify Python code is correct
3. Test Python module directly in Python environment first

### Issue: Test Hangs Forever

**Symptom**: Test never completes, no error

**Cause**: Python code has infinite loop or waiting for resource

**Solution**: Add timeout
```kotlin
@Test(timeout = 10_000) // 10 second timeout
fun testThatMightHang() {
    wrapper.callAttr("potentially_blocking_method")
}
```

## Best Practices Summary

### DO:
- ✅ Use instrumented tests for all Python integration
- ✅ Initialize Python in `@Before` with `Python.isStarted()` check
- ✅ Test type conversions explicitly
- ✅ Handle `PyException` appropriately
- ✅ Close PyObject references
- ✅ Use timeouts for potentially slow operations
- ✅ Test both success and failure paths
- ✅ Verify GIL behavior with concurrent tests

### DON'T:
- ❌ Try to use Python in JVM unit tests
- ❌ Assume callbacks work reliably (test polling alternative)
- ❌ Forget to start Python
- ❌ Leak PyObject references
- ❌ Test daemon thread internals directly
- ❌ Make assumptions about thread safety (verify with tests)

---

*Based on real-world Chaquopy usage in production Android apps*
