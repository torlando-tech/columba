# Threading Troubleshooting Guide

## Document Purpose

This guide provides solutions to common threading issues encountered in the Columba Android app, organized by symptom for quick diagnosis and resolution.

## Diagnosis Process

When encountering a threading issue:

1. **Identify the symptom** (slow, crashes, hangs, etc.)
2. **Check relevant section** in this guide
3. **Gather diagnostic info** (logs, thread dumps, profiler)
4. **Apply solution** from guide
5. **Verify fix** with tests

---

## Symptom: Slow Initialization (> 3 seconds)

### Possible Causes

#### 1. Main Thread Handler Queue Backup

**Symptoms**:
- Initialization timeout (30s, 60s)
- Logs show task completed AFTER timeout
- Other UI operations also slow

**Diagnosis**:
```kotlin
// Add timing logs
val postTime = SystemClock.elapsedRealtime()
Handler(Looper.getMainLooper()).post {
    val delay = SystemClock.elapsedRealtime() - postTime
    Log.d("Timing", "Handler delay: ${delay}ms")  // If > 1000ms, queue is backed up!
}
```

**Root Cause**: Main thread extremely busy, Handler tasks queue for long time

**Solution**: Don't use main thread for initialization
```kotlin
// ❌ BEFORE
Handler(Looper.getMainLooper()).post {
    wrapper.callAttr("initialize", config)
}

// ✅ AFTER
withContext(Dispatchers.IO) {
    wrapper.callAttr("initialize", config)
}
```

**Reference**: THREADING_ARCHITECTURE_ANALYSIS.md:20-60

#### 2. Python Call Without Timeout

**Symptoms**:
- Sometimes initializes fast, sometimes hangs forever
- No error message, just waiting

**Diagnosis**:
```bash
# Check if Python process is running
adb shell ps | grep python

# Check thread state
adb shell ps -T | grep PythonWorker
```

**Root Cause**: Python code stuck in infinite loop or waiting for resource

**Solution**: Add timeout
```kotlin
withTimeout(30_000) {  // 30 second timeout
    PythonExecutor.execute {
        wrapper.callAttr("initialize", config)
    }
}
```

#### 3. Synchronous IPC Blocking

**Symptoms**:
- Initialization waits for other IPC calls
- Multiple services competing for binder threads

**Diagnosis**:
```kotlin
// Add logging around IPC calls
Log.d("IPC", "Calling initialize...")
val result = protocol.initialize(config)
Log.d("IPC", "Initialize returned: $result")

// Long delay between logs = blocking
```

**Root Cause**: runBlocking in AIDL methods blocks binder threads

**Solution**: Convert to async (see Pattern: Sync to Async IPC)

---

## Symptom: Application Not Responding (ANR)

### Possible Causes

#### 1. Main Thread Blocked

**Symptoms**:
- "Application is not responding" dialog
- UI completely frozen
- Logs: "Skipped X frames!"

**Diagnosis**:
```bash
# Pull ANR trace
adb pull /data/anr/traces.txt

# Look for main thread in trace
grep -A 20 "main" traces.txt
```

**Root Cause**: Long operation on main thread

**Solution**: Move to background thread
```kotlin
// ❌ WRONG
fun onClick() {
    val data = processLargeFile()  // Blocks main thread!
    updateUI(data)
}

// ✅ CORRECT
fun onClick() {
    viewModelScope.launch {
        val data = withContext(Dispatchers.IO) {
            processLargeFile()  // Off main thread
        }
        updateUI(data)  // Back on main thread
    }
}
```

#### 2. Binder Thread Pool Exhausted

**Symptoms**:
- ANR in Service class
- Many concurrent IPC calls
- Logs: "Binder threads exhausted"

**Diagnosis**:
```bash
# Check binder threads
adb shell ps -T -p <service_pid> | grep Binder

# Should see ~16 threads, if all in D state (blocked), pool is exhausted
```

**Root Cause**: AIDL methods using runBlocking

**Solution**: Remove runBlocking from all AIDL methods
```kotlin
// ❌ WRONG
override fun doWork(): String {
    return runBlocking { heavyWork() }  // Blocks binder thread!
}

// ✅ CORRECT
override fun doWork(callback: IWorkCallback) {
    serviceScope.launch {
        val result = heavyWork()
        callback.onComplete(result)
    }
}
```

**Quick Fix Script**:
```bash
# Find all runBlocking in service layer
grep -r "runBlocking" app/src/main/java/*/service/ --include="*.kt"

# Each instance needs to be converted to async
```

---

## Symptom: High CPU Usage When Idle

### Possible Causes

#### 1. Polling Loops

**Symptoms**:
- CPU usage 5-10% when app idle
- Battery drains faster than expected
- Logs show periodic operations

**Diagnosis**:
```kotlin
// Add logging to suspected polling code
while (isActive) {
    Log.d("Polling", "Checking status...")  // If this appears frequently, found it!
    checkStatus()
    delay(100)
}
```

**Root Cause**: Polling with short delay wakes CPU frequently

**Solution**: Convert to event-driven
```kotlin
// ❌ WRONG: Polling
while (isActive) {
    if (getStatus() == READY) break
    delay(100)
}

// ✅ CORRECT: Event-driven
networkStatus.first { it == NetworkStatus.READY }
```

#### 2. Too Many Background Threads

**Symptoms**:
- Many threads active (> 50)
- CPU usage spread across threads
- Battery drain

**Diagnosis**:
```bash
# Count threads
adb shell ps -T -p <pid> | wc -l

# If > 50 threads, probably creating too many
```

**Root Cause**: Creating too many threads or not reusing dispatcher pools

**Solution**: Use shared dispatchers
```kotlin
// ❌ WRONG: Creating many threads
repeat(100) {
    thread {  // 100 threads!
        doWork()
    }
}

// ✅ CORRECT: Use dispatcher pool
repeat(100) {
    launch(Dispatchers.Default) {  // Reuses thread pool
        doWork()
    }
}
```

---

## Symptom: Dropped Frames / Janky UI

### Possible Causes

#### 1. Main Thread Blocking

**Symptoms**:
- UI animations stutter
- Scrolling not smooth
- Logs: "Skipped 30 frames!"

**Diagnosis**:
```kotlin
// Monitor frame timing
Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
    override fun doFrame(frameTimeNanos: Long) {
        if (lastFrameTime > 0) {
            val delta = (frameTimeNanos - lastFrameTime) / 1_000_000
            if (delta > 16) {
                Log.w("Frames", "Dropped frames! Delta: ${delta}ms")
            }
        }
        lastFrameTime = frameTimeNanos
        Choreographer.getInstance().postFrameCallback(this)
    }
})
```

**Root Cause**: Operations taking > 16ms on main thread

**Solution**: Profile and move slow operations off main thread
```kotlin
// Use Android Profiler to find slow operations
// Move them to background dispatcher
withContext(Dispatchers.Default) {
    slowOperation()
}
```

#### 2. Compose Recomposition Issues

**Symptoms**:
- UI updates slow
- Entire screen redraws frequently

**Diagnosis**:
```kotlin
// Add composition tracking
@Composable
fun MyScreen() {
    Log.d("Compose", "Recomposing MyScreen")  // If this logs frequently, problem found
    // ...
}
```

**Solution**: Optimize state management
```kotlin
// ❌ WRONG: Entire object changes
data class UiState(val field1: String, val field2: Int)
_uiState.value = uiState.copy(field1 = "new")  // Entire state changes!

// ✅ BETTER: Use StateFlow per field
val field1: StateFlow<String>
val field2: StateFlow<Int>
```

---

## Symptom: Memory Leaks

### Possible Causes

#### 1. Coroutine Scope Not Cancelled

**Symptoms**:
- Memory grows over time
- Threads don't stop after Activity/Service destroyed
- LeakCanary reports leaks

**Diagnosis**:
```kotlin
// Check if scope is cancelled
override fun onDestroy() {
    Log.d("Lifecycle", "onDestroy called")
    Log.d("Lifecycle", "Scope active: ${serviceScope.isActive}")  // Should be false
    super.onDestroy()
}
```

**Root Cause**: CoroutineScope not cancelled in onDestroy

**Solution**: Cancel scope properly
```kotlin
class MyService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob())

    override fun onDestroy() {
        serviceScope.cancel()  // Cancel all coroutines
        super.onDestroy()
    }
}
```

#### 2. PyObject References Not Released

**Symptoms**:
- Memory grows when calling Python
- Python heap grows
- OOM (Out Of Memory) errors

**Diagnosis**:
```python
# Add to Python side
import gc
import sys

def get_object_count():
    return len(gc.get_objects())

# Call periodically and log
```

**Root Cause**: PyObject references held by Kotlin code

**Solution**: Explicitly close PyObjects
```kotlin
fun processPythonData() {
    val data: PyObject? = null
    try {
        data = wrapper.callAttr("get_data")
        processData(data)
    } finally {
        data?.close()  // Release Python reference
    }
}
```

#### 3. Callback Holding Strong Reference

**Symptoms**:
- Activity/Fragment not garbage collected
- Context leaks
- "Instance of Activity leaked"

**Root Cause**: Callback holds reference to Context

**Solution**: Use weak reference or lifecycle-aware components
```kotlin
// ❌ WRONG
val callback = object : ICallback {
    override fun onData(data: String) {
        activity.updateUI(data)  // Holds strong reference to activity!
    }
}

// ✅ CORRECT
val callback = object : ICallback {
    override fun onData(data: String) {
        activity.get()?.updateUI(data)  // Weak reference
    }
}
```

---

## Symptom: Race Conditions

### Possible Causes

#### 1. Shared Mutable State

**Symptoms**:
- Inconsistent behavior (works sometimes, not others)
- Data corruption
- Hard to reproduce bugs

**Diagnosis**:
```kotlin
// Add logging with thread information
Log.d("Race", "[${Thread.currentThread().name}] Setting value: $newValue")
sharedValue = newValue
```

**Root Cause**: Multiple threads modifying shared state without synchronization

**Solution**: Use thread-safe patterns
```kotlin
// ❌ WRONG: Mutable var accessed from multiple threads
private var status: NetworkStatus = NetworkStatus.SHUTDOWN

// ✅ CORRECT: Thread-safe StateFlow
private val _status = MutableStateFlow<NetworkStatus>(NetworkStatus.SHUTDOWN)
val status: StateFlow<NetworkStatus> = _status.asStateFlow()
```

#### 2. Cross-Process Race Condition

**Symptoms**:
- Both processes trying to initialize
- One fails with "already initialized"
- Timing-dependent behavior

**Diagnosis**:
```bash
# Check logs from both processes
adb logcat | grep "Initializing"

# If both processes log initialization, race condition exists
```

**Root Cause**: No clear ownership of initialization

**Solution**: Designate single initializer (Phase 4.1)
```kotlin
// Only Service process initializes
class ReticulumService : Service() {
    override fun onCreate() {
        super.onCreate()
        initializeReticulum()  // Service owns initialization
    }
}

// App process only observes status
class ColumbaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Don't initialize, just observe
        observeReticulumStatus()
    }
}
```

---

## Symptom: Tests Failing Intermittently

### Possible Causes

#### 1. Race Conditions in Tests

**Symptoms**:
- Test passes sometimes, fails others
- "Expected X but was Y"
- Timing-sensitive tests

**Root Cause**: Test doesn't wait for async operations

**Solution**: Use runTest and proper synchronization
```kotlin
@Test
fun testAsyncOperation() = runTest {
    val result = async { performOperation() }

    // ❌ WRONG: Immediate assertion
    // assertEquals(expected, result.getCompleted())  // May not be done yet!

    // ✅ CORRECT: Wait for completion
    val actual = result.await()
    assertEquals(expected, actual)
}
```

#### 2. Shared Test State

**Symptoms**:
- Tests pass individually, fail when run together
- Order-dependent failures

**Root Cause**: Tests sharing mutable state

**Solution**: Isolate test state
```kotlin
class MyTest {
    // ❌ WRONG: Shared state
    companion object {
        val sharedData = mutableListOf<String>()
    }

    // ✅ CORRECT: Per-test state
    @Before
    fun setUp() {
        val testData = mutableListOf<String>()
    }
}
```

---

## Symptom: Status Never Reaches READY

### Possible Causes

#### 1. Status Update Lost

**Symptoms**:
- Initialization completes successfully
- Logs show "Status: READY"
- UI still shows "Connecting..."

**Diagnosis**:
```kotlin
// Add logging around status updates
_networkStatus.value = NetworkStatus.READY
Log.d("Status", "Status set to READY, collectors: ${_networkStatus.subscriptionCount.value}")
```

**Root Cause**: No active collectors when status emitted

**Solution**: Use StateFlow (retains latest value)
```kotlin
// StateFlow always has a value
val status: StateFlow<NetworkStatus> = _networkStatus.asStateFlow()

// New collectors immediately get current value
status.collect { currentStatus ->
    // Gets latest status even if emitted earlier
}
```

#### 2. Status Check Race Condition

**Symptoms**:
- Sometimes works, sometimes doesn't
- Timing-dependent

**Root Cause**: Checking status before subscription active

**Solution**: Wait for specific state
```kotlin
// ❌ WRONG: Check current value
if (protocol.getStatus() == NetworkStatus.READY) {
    // May miss transition if it happened before this check
}

// ✅ CORRECT: Wait for state
protocol.networkStatus.first { it == NetworkStatus.READY }
// Guaranteed to catch transition
```

---

## Symptom: Python Calls Hang Forever

### Possible Causes

#### 1. No Timeout Set

**Symptoms**:
- Call never returns
- No error message
- Process stuck

**Diagnosis**:
```bash
# Check Python thread state
adb shell ps -T -p <pid> | grep Python

# If in D state (disk sleep), likely blocked on I/O
```

**Solution**: Always use timeout
```kotlin
withTimeout(30_000) {
    PythonExecutor.execute {
        wrapper.callAttr("method")
    }
}
```

#### 2. GIL Deadlock (Rare)

**Symptoms**:
- All Python calls hang
- Python thread in wait state

**Diagnosis**:
```python
# Add to Python code
import threading
print(f"Active threads: {threading.active_count()}")
for thread in threading.enumerate():
    print(f"  {thread.name}: {thread.is_alive()}")
```

**Solution**: Review Python code for deadlock (lock acquisition order)

---

## Diagnostic Tools

### LogCat Filtering

```bash
# Filter by tag
adb logcat Threading:D *:S

# Filter by PID
adb logcat --pid=<pid>

# Filter by process name
adb logcat --pid=$(adb shell pidof com.lxmf.messenger)

# Multiple filters
adb logcat Threading:D Coroutines:D *:S
```

### Thread Dump

```bash
# Get thread dump
adb shell kill -3 <pid>
adb pull /data/anr/traces.txt

# Or using debugger
jstack <pid>
```

### Android Profiler

1. Open Android Studio
2. View → Tool Windows → Profiler
3. Select process
4. Click "CPU" to see thread activity
5. Look for:
   - Main thread blocking
   - Too many threads
   - Thread contention

### Memory Profiler

1. Profiler → Memory
2. Force GC
3. Capture heap dump
4. Look for:
   - Leaked Activities/Fragments
   - Growing collections
   - Retained PyObjects

### Network Profiler

1. Profiler → Network
2. Check if network calls on main thread
3. Look for timing issues

---

## Prevention Checklist

Before committing threading-related code:

- [ ] No `runBlocking` in production code
- [ ] All Python calls have timeout
- [ ] Status distributed via StateFlow/SharedFlow
- [ ] Coroutine scopes cancelled in onDestroy
- [ ] No operations > 16ms on main thread
- [ ] Tests use runTest
- [ ] Logged thread context for debugging
- [ ] Documented threading contracts

---

## Emergency Quick Fixes

### If Users Experiencing ANRs

**Immediate**:
```kotlin
// Add timeouts to all Python calls
withTimeout(10_000) {  // Aggressive 10s timeout
    pythonCall()
}
```

### If Initialization Failing

**Immediate**:
```kotlin
// Remove Handler.post() if present
// Call directly on IO dispatcher
withContext(Dispatchers.IO) {
    initialize()
}
```

### If High CPU Usage

**Immediate**:
```kotlin
// Increase polling interval
delay(5.seconds)  // Instead of delay(100.milliseconds)

// Or pause polling when app backgrounded
```

### If Memory Leaking

**Immediate**:
```kotlin
// Cancel scopes aggressively
override fun onDestroy() {
    scope.cancel()
    scope = null  // Ensure no retention
    super.onDestroy()
}
```

---

## Getting More Help

If issue not covered in this guide:

1. **Check existing plan documents**:
   - THREADING_REDESIGN_PLAN.md
   - THREADING_ARCHITECTURE_ANALYSIS.md

2. **Review pattern examples** in `patterns/` directory

3. **Check phase guides** in `phases/` directory

4. **Enable verbose logging**:
   ```kotlin
   Log.setLoggingLevel(Log.VERBOSE)
   ```

5. **Create minimal reproduction case**

6. **Collect diagnostic information**:
   - Thread dumps
   - LogCat output
   - Profiler screenshots
   - Timing measurements

---

*Last Updated: 2025-10-27*
