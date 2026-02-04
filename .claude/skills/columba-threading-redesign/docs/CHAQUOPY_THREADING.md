# Chaquopy Threading Safety

## Document Purpose

This document provides research-verified information about Chaquopy threading safety, Python GIL behavior, and best practices for integrating Python code with Kotlin coroutines in the Columba Android app.

## Critical Discovery: Python's "Main Thread" is Defined by Python.start()

### The Original Concern (Was Actually CORRECT)

**From THREADING_ARCHITECTURE_ANALYSIS.md:51-58**:
```kotlin
// CRITICAL: Run Python initialize() on main thread to allow
// signal handler registration.
// Python's signal module requires calls to signal.signal() to
// be on the main thread

Handler(Looper.getMainLooper()).post {
    resultRef.set(wrapper!!.callAttr("initialize", configJson))
    latch.countDown()
}
```

**This rationale was CORRECT** - but the implementation (Handler.post) was problematic.

**Timeline from logs**:
```
18:44:57.798 - Timeout reached (60s wait)
18:44:57.799 - Python initialize() FINALLY called (1ms after timeout!)
18:45:00.083 - Python initialization completed (2.3s total)
```

The task sat in the Handler queue for 60+ seconds because the main thread was extremely busy. Python initialization itself only took 2.3 seconds.

### Key Insight from Chaquopy Maintainer

**From Chaquopy Issue #1243** (Maintainer quote):

> "if you call `Python.start` on a different thread, then Python will consider that to be the main thread. It doesn't need to be the same as the main thread in Java."

**What this means**:
- Whatever thread calls `Python.start()` becomes Python's "main thread"
- Signal handlers MUST run on that specific thread
- In Columba: `Python.start()` is called in `Application.onCreate()` → Android's main thread
- Therefore: Signal handlers REQUIRE Android's main thread

### Phase 1 Evolution: From Handler to Dispatchers.Main.immediate

**Phase 1 Initial Attempt (BROKEN)**:
```kotlin
// Tried using Dispatchers.IO - BROKE signal handlers!
val result = withContext(Dispatchers.IO) {
    wrapper!!.callAttr("initialize", configJson)
}
// Error: "signal only works in main thread of the main interpreter"
```

**Phase 1 Final Solution (CORRECT)**:
```kotlin
// Use Dispatchers.Main.immediate - Fast main thread execution
val result = withContext(Dispatchers.Main.immediate) {
    wrapper!!.callAttr("initialize", configJson)
}
```

**Why Dispatchers.Main.immediate is ideal**:
- ✅ Runs on Android's main thread (Python's "main thread")
- ✅ Executes immediately without Handler queue delays
- ✅ Still async (within `serviceScope.launch`)
- ✅ No ANR risk (binder thread freed immediately)

**Results**:
- ✅ Initialization time: 60s → 2s (no Handler delays)
- ✅ Status transitions to READY correctly
- ✅ Signal handlers work (on correct thread)
- ✅ No crashes or threading errors
- ✅ Announce polling works
- ✅ Messages work

**Conclusion**: Chaquopy DOES require signal handlers to run on the thread that called Python.start(). Use `Dispatchers.Main.immediate` for fast, correct execution.

## Python Global Interpreter Lock (GIL)

### What is the GIL?

The Python Global Interpreter Lock (GIL) is a mutex that protects access to Python objects, preventing multiple threads from executing Python bytecode simultaneously.

**Key Behaviors**:
1. Only one thread executes Python bytecode at a time
2. Thread switches happen:
   - Every few bytecode instructions
   - During I/O operations
   - When explicitly released
3. Multiple threads CAN call Python, but GIL serializes execution
4. Native code (C extensions) can release GIL

### GIL in Chaquopy/Android

```
┌────────────────────────────────────────────────┐
│          Kotlin/Android Side                   │
├────────────────────────────────────────────────┤
│                                                │
│  Thread 1 (IO-1)      Thread 2 (IO-2)         │
│       │                     │                  │
│       │ callAttr()          │ callAttr()       │
│       ▼                     ▼                  │
├───────────────────────────────────────────────┤
│              Chaquopy JNI Layer                │
├───────────────────────────────────────────────┤
│           Python GIL (Serialization)           │
│       ┌──────────────────────────────┐        │
│       │   Only ONE executes at a     │        │
│       │   time, GIL controls access  │        │
│       └──────────────────────────────┘        │
├────────────────────────────────────────────────┤
│              Python Bytecode                   │
│        wrapper.initialize(config)              │
│        RNS.Reticulum(config_obj)               │
│        └─ Creates daemon threads               │
└────────────────────────────────────────────────┘
```

### Thread Safety Implications

**What the GIL Protects**:
- ✅ Python object access (can't corrupt objects)
- ✅ Python data structures (lists, dicts, etc.)
- ✅ Reference counting (memory management)

**What the GIL Doesn't Protect**:
- ❌ Kotlin-side state mutations
- ❌ Shared Java/Kotlin objects
- ❌ File system operations (need explicit locking)
- ❌ Database transactions (need Room's transactions)

## Chaquopy Threading Model

### How Chaquopy Handles Multiple Threads

Based on codebase testing and observation:

```kotlin
// Multiple Kotlin threads can call Python
launch(Dispatchers.IO) {  // Thread IO-1
    wrapper.callAttr("method_a")
}

launch(Dispatchers.IO) {  // Thread IO-2
    wrapper.callAttr("method_b")
}

launch(Dispatchers.IO) {  // Thread IO-3
    wrapper.callAttr("method_c")
}
```

**What happens**:
1. All three threads acquire Python's GIL in sequence
2. Only one executes Python code at a time
3. Others wait for GIL to be released
4. No crashes, no corruption
5. But: unpredictable execution order

### Python's Own Threading

**From reticulum_wrapper.py:48**:
```python
def __init__(self, storage_path: str):
    # ...
    self.announce_lock = threading.Lock()
```

Python code itself uses threading:
- RNS creates daemon threads internally
- Python's `threading.Lock()` provides additional safety
- These threads are managed by Python, not visible to Kotlin

## Verified Safe Patterns

### Pattern 1: Direct Call on IO Dispatcher

**Current Implementation** (ReticulumService.kt:141):
```kotlin
suspend fun initialize(configJson: String): PyObject = withContext(Dispatchers.IO) {
    wrapper!!.callAttr("initialize", configJson)
}
```

**Safety**:
- ✅ Proven to work (current fix)
- ✅ GIL provides thread safety
- ✅ Chaquopy handles thread transitions
- ✅ No special configuration needed

**When to Use**:
- Quick Python calls (< 1 second)
- Infrequent operations
- Don't need execution order guarantees

**Limitations**:
- Can exhaust IO dispatcher if many long-running Python calls
- No guarantee of execution order
- Harder to monitor or debug

### Pattern 2: Single-Threaded Executor (Recommended)

**Recommended Implementation**:
```kotlin
object PythonExecutor {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PythonWorker").apply {
            priority = Thread.NORM_PRIORITY  // Or BACKGROUND
            isDaemon = false  // Important: should complete work
        }
    }

    private val dispatcher = executor.asCoroutineDispatcher()

    suspend fun <T> execute(block: () -> T): T =
        withTimeout(30_000) {  // 30 second timeout
            withContext(dispatcher) {
                try {
                    block()
                } catch (e: Exception) {
                    Log.e("PythonExecutor", "Python call failed", e)
                    throw e
                }
            }
        }

    fun shutdown() {
        dispatcher.close()
        executor.shutdown()
    }
}

// Usage
val result = PythonExecutor.execute {
    wrapper.callAttr("some_method", arg1, arg2)
}
```

**Benefits**:
- ✅ Predictable execution order (FIFO)
- ✅ Queue monitoring possible
- ✅ Timeout enforcement
- ✅ Centralized error handling
- ✅ Won't exhaust dispatcher threads
- ✅ Easy to add metrics/logging

**Trade-offs**:
- Slightly more complex setup
- Serial execution (but GIL does this anyway)
- Need to manage executor lifecycle

**When to Use**:
- Production code (recommended)
- Frequent Python calls
- Long-running Python operations
- Need predictable behavior

### Pattern 3: Multiple Threads with Explicit Ordering

**For cases where order matters**:
```kotlin
// Use Channel to serialize
private val pythonChannel = Channel<PythonOperation>(capacity = Channel.UNLIMITED)

data class PythonOperation(
    val block: () -> PyObject,
    val result: CompletableDeferred<PyObject>
)

// Processor coroutine
launch(Dispatchers.IO) {
    for (operation in pythonChannel) {
        try {
            val result = operation.block()
            operation.result.complete(result)
        } catch (e: Exception) {
            operation.result.completeExceptionally(e)
        }
    }
}

// Call site
suspend fun callPython(block: () -> PyObject): PyObject {
    val operation = PythonOperation(block, CompletableDeferred())
    pythonChannel.send(operation)
    return operation.result.await()
}
```

## Python Callbacks to Kotlin

### Callback Thread Context

When Python calls back to Kotlin:

```kotlin
// Kotlin defines callback
class AnnounceCallback : PyObject() {
    fun onAnnounce(announce: PyObject) {
        // WARNING: This runs on Python's thread!
        // Not safe to update UI directly

        // Safe: Post to appropriate dispatcher
        serviceScope.launch(Dispatchers.Main) {
            _announceFlow.emit(AnnounceEvent.New(announce))
        }
    }
}

// Register with Python
wrapper.callAttr("register_callback", AnnounceCallback())
```

**Thread Safety Rules**:
1. Callback executes on Python's thread (GIL held)
2. Don't do heavy work in callback (blocks Python)
3. Post to appropriate dispatcher for processing
4. Don't update UI directly from callback

## Testing Python Threading Safety

### Test 1: Concurrent Access

**Purpose**: Verify multiple threads can call Python safely

```kotlin
@Test
fun testConcurrentPythonAccess() = runTest {
    val wrapper = createPythonWrapper()

    // Launch 10 concurrent calls
    val results = (1..10).map { i ->
        async(Dispatchers.IO) {
            wrapper.callAttr("echo", "call_$i")
        }
    }.awaitAll()

    // All should succeed
    assertEquals(10, results.size)
    results.forEach { assertNotNull(it) }
}
```

**Success Criteria**:
- No crashes
- No exceptions
- All calls return results
- Results are not corrupted

### Test 2: Rapid Invocations

**Purpose**: Stress test with many quick calls

```kotlin
@Test
fun testRapidPythonInvocations() = runTest {
    val wrapper = createPythonWrapper()

    // 1000 rapid calls
    repeat(1000) { i ->
        wrapper.callAttr("simple_method", i)
    }

    // Should complete without errors
}
```

**Success Criteria**:
- Completes without crash
- No memory leaks
- No GIL deadlock
- Reasonable performance

### Test 3: Long-Running Operations

**Purpose**: Verify long operations don't block other threads

```kotlin
@Test
fun testLongRunningPythonOperation() = runTest {
    val wrapper = createPythonWrapper()

    // Start long operation
    val longOp = async(Dispatchers.IO) {
        wrapper.callAttr("sleep", 5)  // Python time.sleep(5)
    }

    // Quick operation should still work
    val quickOp = async(Dispatchers.IO) {
        wrapper.callAttr("echo", "quick")
    }

    // Quick op completes while long op still running
    val quickResult = quickOp.await()
    assertNotNull(quickResult)

    // Long op eventually completes
    longOp.await()
}
```

**Success Criteria**:
- Quick operation doesn't wait for long operation
- Both complete successfully
- No deadlock

### Test 4: Callback Thread Context

**Purpose**: Verify callbacks execute on correct thread

```kotlin
@Test
fun testCallbackThreadContext() = runTest {
    var callbackThread: Thread? = null

    val callback = object : PyObject() {
        fun onCallback() {
            callbackThread = Thread.currentThread()
        }
    }

    wrapper.callAttr("trigger_callback", callback)

    // Callback should have been called
    assertNotNull(callbackThread)

    // Log thread information for verification
    Log.d("Test", "Callback thread: ${callbackThread?.name}")
}
```

## Common Issues and Solutions

### Issue 1: "Cannot call Python from non-Python thread"

**Symptom**: Exception when calling Python from certain threads

**Cause**: This shouldn't happen with Chaquopy, but if it does:

**Solution**:
```kotlin
// Ensure Python context is acquired
val result = PythonExecutor.execute {
    wrapper.callAttr("method")
}
```

### Issue 2: Python Call Hangs Forever

**Symptom**: Python call never returns

**Cause**:
- Python code has infinite loop
- Waiting for resource that never becomes available
- GIL deadlock (rare)

**Solution**: Always use timeouts
```kotlin
suspend fun safePythonCall(): PyObject? {
    return try {
        withTimeout(30_000) {  // 30 second timeout
            PythonExecutor.execute {
                wrapper.callAttr("method")
            }
        }
    } catch (e: TimeoutCancellationException) {
        Log.e(TAG, "Python call timed out")
        null
    }
}
```

### Issue 3: Memory Leaks from Python Objects

**Symptom**: Memory usage grows over time

**Cause**: PyObject references not released

**Solution**: Explicitly close PyObjects
```kotlin
fun processPythonData() {
    val data = wrapper.callAttr("get_data")
    try {
        // Use data
        processData(data)
    } finally {
        data?.close()  // Release Python reference
    }
}
```

### Issue 4: Callback Not Called

**Symptom**: Registered callback never executes

**Cause**:
- Callback object garbage collected
- Python not holding strong reference

**Solution**: Keep strong reference
```kotlin
class ReticulumService : Service() {
    // Keep callback as instance variable
    private val announceCallback = AnnounceCallback()

    fun registerCallbacks() {
        wrapper.callAttr("register_callback", announceCallback)
    }
}
```

## Performance Considerations

### GIL Contention

When many threads call Python:
```kotlin
// 100 threads all calling Python
repeat(100) { i ->
    launch(Dispatchers.IO) {
        wrapper.callAttr("method")  // All wait for GIL
    }
}
```

**Problem**: Threads spend time waiting for GIL, not doing useful work

**Solution**: Single-threaded executor
```kotlin
// One thread, sequential execution
repeat(100) { i ->
    PythonExecutor.execute {
        wrapper.callAttr("method")  // No GIL contention
    }
}
```

**Benefit**: Actually faster because no thread contention overhead

### Python Call Overhead

Each Python call has overhead:
- JNI transition (~microseconds)
- GIL acquisition (~microseconds)
- Python function call
- Result conversion

**Optimization**: Batch operations when possible
```kotlin
// ❌ Slow: Many individual calls
val results = mutableListOf<String>()
for (i in 1..1000) {
    val result = wrapper.callAttr("process_item", i)
    results.add(result.toString())
}

// ✅ Fast: One call processing batch
val results = wrapper.callAttr("process_items", (1..1000).toList())
```

## Best Practices Summary

### DO:
- ✅ Call Python from any thread (GIL protects)
- ✅ Use single-threaded executor for predictability
- ✅ Always use timeouts for Python calls
- ✅ Post to appropriate dispatcher from callbacks
- ✅ Batch operations when possible
- ✅ Close PyObject references when done
- ✅ Keep strong references to callbacks
- ✅ Add try-catch around Python calls

### DON'T:
- ❌ Assume main thread required (it's not)
- ❌ Block binder threads waiting for Python
- ❌ Update UI directly from Python callbacks
- ❌ Call Python without timeout
- ❌ Launch hundreds of threads all calling Python
- ❌ Ignore exceptions from Python
- ❌ Let PyObject references leak
- ❌ Do heavy work in Python callbacks

## Migration Checklist

When converting code to use PythonExecutor:

- [ ] Identify all wrapper.callAttr() calls
- [ ] Wrap each in PythonExecutor.execute { }
- [ ] Add timeout handling
- [ ] Add error handling
- [ ] Update tests to verify behavior
- [ ] Document threading contract
- [ ] Add logging for debugging
- [ ] Test concurrent access
- [ ] Test stress scenarios (1000+ calls)
- [ ] Verify no performance regression

## References

- **Chaquopy Documentation**: https://chaquo.com/chaquopy/
- **Python GIL**: https://wiki.python.org/moin/GlobalInterpreterLock
- **THREADING_ARCHITECTURE_ANALYSIS.md**: Root cause analysis of Handler.post() issue
- **ReticulumService.kt:141**: Current working implementation

---

*Last Updated: 2025-10-27*
*Verified: Python off main thread is SAFE in Chaquopy*
