# Pattern: Python Executor for Thread Safety

> **📝 NOTE: Optional Pattern - Evaluated and Not Implemented in Columba**
>
> This pattern was thoroughly evaluated during Phase 3 and intentionally NOT implemented.
> The current direct Python call approach performs excellently (0.082ms avg, 0% CPU idle).
> Python's GIL already provides single-threaded execution. See `docs/phase3-pythonexecutor-decision.md`.
>
> **This pattern remains valid for projects with different needs** (e.g., high Python call volume,
> need for centralized metrics, timeout requirements). Keep for reference.

## Problem

Multiple threads calling Python concurrently leads to unpredictable order, harder debugging, potential dispatcher exhaustion.

## Solution

Single-threaded executor for all Python calls.

---

## Implementation

**File**: `app/src/main/java/com/lxmf/messenger/threading/PythonExecutor.kt`

```kotlin
object PythonExecutor {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PythonWorker")
    }
    
    private val dispatcher = executor.asCoroutineDispatcher()
    
    suspend fun <T> execute(
        operation: String = "unknown",
        timeoutMs: Long = 30_000,
        block: () -> T
    ): T {
        return withTimeout(timeoutMs) {
            withContext(dispatcher) {
                block()
            }
        }
    }
}
```

## Usage

```kotlin
// ❌ BEFORE: Direct call on IO dispatcher
val result = withContext(Dispatchers.IO) {
    wrapper.callAttr("some_method")
}

// ✅ AFTER: Through executor
val result = PythonExecutor.execute("some_method") {
    wrapper.callAttr("some_method")
}
```

## Benefits

- ✅ Predictable execution order (FIFO)
- ✅ Easy to monitor queue depth
- ✅ Timeout enforcement
- ✅ Prevents dispatcher exhaustion
- ✅ Centralized logging/metrics

---

*One thread for Python = Predictable behavior*
