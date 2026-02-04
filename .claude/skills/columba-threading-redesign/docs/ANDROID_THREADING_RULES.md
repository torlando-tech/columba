# Android Threading Rules

## Document Purpose

This document explains Android-specific threading constraints and best practices relevant to the Columba app's threading redesign, including main thread requirements, binder thread management, and service threading patterns.

## The Android Main Thread

### What is the Main Thread?

The main thread (also called UI thread) is the thread where:
- Application starts (`Application.onCreate()`)
- Activity/Fragment lifecycle callbacks execute
- View drawing and layout happens
- UI event handling occurs (clicks, touches, etc.)

**Critical Rule**: The main thread must remain responsive. Any operation taking > 16ms will drop frames (< 60 FPS).

### Main Thread in Service Processes

**Important Discovery**: Even background services have a main thread!

```
App Process                    Service Process
├─ Main Thread (UI)            ├─ Main Thread (no UI, but exists!)
│  ├─ Activities               │  ├─ Service lifecycle callbacks
│  ├─ Fragments                │  ├─ IPC binder calls
│  └─ Compose UI               │  └─ Handler message queue
```

**From THREADING_ARCHITECTURE_ANALYSIS.md:126-147**:
```
The main thread of the :reticulum service was so busy that Handler
tasks queued for 60+ seconds!
```

This happened in a service process with no UI! The main thread was still busy with:
- Service lifecycle callbacks
- IPC binder calls
- Handler message processing
- Internal Android system work

### Main Thread Constraints

**DO on Main Thread**:
- ✅ Activity/Fragment lifecycle callbacks (onCreate, onResume, etc.)
- ✅ View updates (setText, setVisibility, etc.)
- ✅ Compose recomposition
- ✅ UI event handling (onClick, etc.)
- ✅ Short operations (< 16ms)

**DON'T on Main Thread**:
- ❌ Network calls
- ❌ Database queries (use Room's suspend functions)
- ❌ File I/O
- ❌ Python/Chaquopy calls
- ❌ Complex parsing or encryption
- ❌ Any operation > 16ms

### Monitoring Main Thread Health

**Frame Drop Detection**:
```kotlin
class FrameMonitor {
    private var lastFrameTime = 0L

    fun startMonitoring() {
        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (lastFrameTime > 0) {
                    val frameDelta = frameTimeNanos - lastFrameTime
                    val frameDeltaMs = frameDelta / 1_000_000

                    if (frameDeltaMs > 16) {
                        Log.w("FrameMonitor", "Frame dropped! Delta: ${frameDeltaMs}ms")
                        reportFrameDrop(frameDeltaMs)
                    }
                }

                lastFrameTime = frameTimeNanos
                Choreographer.getInstance().postFrameCallback(this)
            }
        })
    }
}
```

**Main Thread Blocking Detection**:
```kotlin
class MainThreadBlockDetector {
    fun detectBlocking(thresholdMs: Long = 16) {
        val mainHandler = Handler(Looper.getMainLooper())

        // Post task and measure delay
        val postTime = SystemClock.elapsedRealtime()

        mainHandler.post {
            val executionTime = SystemClock.elapsedRealtime()
            val delay = executionTime - postTime

            if (delay > thresholdMs) {
                Log.w(
                    "MainThreadBlock",
                    "Main thread blocked for ${delay}ms! Handler queue backup detected."
                )
            }
        }
    }
}
```

## Binder Threads and IPC

### What are Binder Threads?

Binder is Android's Inter-Process Communication (IPC) mechanism. When one process calls another via AIDL, the call executes on a **binder thread** in the receiving process.

```
App Process                         Service Process
    │                                    │
    │  protocol.initialize(config)      │
    │────────────────────────────────►  │
    │         IPC via Binder             │
    │                                    │
    │                               Executes on
    │                               Binder Thread
    │                               (from pool)
    │  ◄───────────────────────────────┤
    │       Returns result               │
```

### Binder Thread Pool

- **Size**: Typically 16 threads per process
- **Shared**: All IPC calls share this pool
- **Limited**: Can be exhausted if threads block
- **Timeout**: ~5 seconds before ANR (Application Not Responding)

### The runBlocking Problem

**Current Issue** (ReticulumService.kt:85):
```kotlin
// AIDL implementation
override fun initialize(configJson: String): String {
    return runBlocking {  // ❌ BLOCKS binder thread!
        serviceScope.async {
            // Long-running Python operation (2+ seconds)
            val result = wrapper!!.callAttr("initialize", configJson)
            result.toString()
        }.await()
    }
}
```

**What happens**:
1. App calls `initialize()` via IPC
2. Call executes on binder thread in service process
3. `runBlocking` blocks that binder thread
4. Thread waits for Python operation (2+ seconds)
5. Other IPC calls wait for available binder thread
6. If all 16 threads blocked → ANR!

**Timeline**:
```
T+0ms:   App calls initialize() via IPC
T+1ms:   Binder thread 1 starts executing
T+2ms:   runBlocking blocks thread 1
T+3ms:   Python operation starts (takes 2000ms)
...      Thread 1 blocked waiting...
T+2003ms: Python completes, thread 1 unblocks
T+2004ms: Result returned to app
```

**If many concurrent calls**:
```
16 binder threads all blocked
    ↓
No threads available for new IPC calls
    ↓
App hangs waiting for IPC
    ↓
ANR after 5 seconds
```

### The Correct Pattern: Async with Callbacks

**Solution**:
```kotlin
// Define callback in AIDL
interface IInitializationCallback {
    void onSuccess(String result);
    void onError(String error);
}

// AIDL method signature
void initialize(String configJson, IInitializationCallback callback);

// Implementation
override fun initialize(configJson: String, callback: IInitializationCallback) {
    // Binder thread returns IMMEDIATELY
    serviceScope.launch {
        try {
            val result = PythonExecutor.execute {
                wrapper!!.callAttr("initialize", configJson)
            }
            callback.onSuccess(result.toString())
        } catch (e: Exception) {
            callback.onError(e.message ?: "Unknown error")
        }
    }
}
```

**Timeline**:
```
T+0ms:   App calls initialize() via IPC
T+1ms:   Binder thread starts executing
T+2ms:   Launch coroutine (non-blocking)
T+3ms:   Binder thread returns (FAST!)
         Thread available for other IPC calls
...      Python runs on separate thread...
T+2000ms: Python completes
T+2001ms: Callback fired to app
```

**Benefits**:
- ✅ Binder thread returns in < 1ms
- ✅ No thread pool exhaustion
- ✅ No ANR risk
- ✅ Can handle many concurrent calls
- ✅ Better error handling

## Service Threading Patterns

### Service Lifecycle Threads

Service lifecycle callbacks run on main thread:

```kotlin
class ReticulumService : Service() {
    // All these run on MAIN THREAD
    override fun onCreate() {
        super.onCreate()
        // Must be fast (< 5ms)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must be fast (< 5ms)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Must be fast (< 1ms)
        return binder
    }

    override fun onDestroy() {
        // Must be fast (< 5ms)
        super.onDestroy()
    }
}
```

**Rule**: Keep all lifecycle callbacks fast. Launch coroutines for any real work.

### Service with Coroutine Scope

**Recommended Pattern**:
```kotlin
class ReticulumService : Service() {
    // SupervisorJob: One failure doesn't cancel others
    private val serviceScope = CoroutineScope(
        Dispatchers.Default + SupervisorJob()
    )

    override fun onCreate() {
        super.onCreate()

        // Launch long-running work
        serviceScope.launch {
            initializeSubsystems()
        }

        serviceScope.launch {
            monitorNetworkStatus()
        }
    }

    override fun onDestroy() {
        // Cancel all work
        serviceScope.cancel()
        super.onDestroy()
    }
}
```

### Foreground Service Considerations

```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // Create notification (must be on main thread initially)
    val notification = createNotification()

    // Start foreground BEFORE heavy work
    startForeground(NOTIFICATION_ID, notification)

    // Now safe to do background work
    serviceScope.launch {
        initializeReticulum()
    }

    return START_STICKY
}
```

## HandlerThread Pattern

### When to Use HandlerThread

Use HandlerThread for:
- Serial execution required (one task at a time)
- Need specific thread priority
- Want dedicated thread with Looper/Handler

**Example: Dedicated Python Thread**:
```kotlin
class PythonHandlerThread : HandlerThread(
    "PythonThread",
    Process.THREAD_PRIORITY_DEFAULT
) {
    private lateinit var handler: Handler

    override fun onLooperPrepared() {
        handler = Handler(looper)
    }

    fun post(task: () -> Unit) {
        handler.post {
            task()
        }
    }

    fun postDelayed(delayMs: Long, task: () -> Unit) {
        handler.postDelayed({
            task()
        }, delayMs)
    }
}

// Usage
val pythonThread = PythonHandlerThread().apply { start() }

pythonThread.post {
    // Executes on dedicated thread
    wrapper.callAttr("some_method")
}
```

### HandlerThread vs Executor

| Feature | HandlerThread | Executor |
|---------|---------------|----------|
| Looper/Handler | ✅ Has Looper | ❌ No Looper |
| Post to thread | `handler.post()` | `executor.submit()` |
| Delayed tasks | ✅ Easy (`postDelayed`) | ❌ Need ScheduledExecutor |
| Integration with Android | ✅ Natural | ⚠️ Need coroutine wrapper |
| Complexity | Medium | Low |

**Recommendation for Columba**: Use Executor with coroutine dispatcher (simpler, more Kotlin-idiomatic).

## Thread Priorities

### Android Thread Priority Levels

```kotlin
// From android.os.Process
THREAD_PRIORITY_DEFAULT          // 0 (normal)
THREAD_PRIORITY_AUDIO           // -16 (high)
THREAD_PRIORITY_FOREGROUND      // -2
THREAD_PRIORITY_BACKGROUND      // 10 (low)
THREAD_PRIORITY_LOWEST          // 19 (lowest)
```

### Setting Thread Priority

```kotlin
// In Executor
val executor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "PythonWorker").apply {
        // Set Android priority
        Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
    }
}

// In HandlerThread
val handlerThread = HandlerThread(
    "PythonThread",
    Process.THREAD_PRIORITY_BACKGROUND  // Lower priority for Python
)
```

### Priority Strategy for Columba

| Thread Type | Priority | Reasoning |
|-------------|----------|-----------|
| Main/UI | DEFAULT | Must be responsive |
| Binder threads | FOREGROUND | IPC should be fast |
| Python executor | DEFAULT or BACKGROUND | Not urgent, can be lower |
| Database | IO dispatcher (default) | Room handles this |
| Polling/monitoring | BACKGROUND | Low priority background work |

## ANR (Application Not Responding)

### What Causes ANRs?

ANR occurs when:
1. Main thread blocked > 5 seconds (input event not processed)
2. Binder call takes > 5 seconds
3. Service not responding to start command
4. BroadcastReceiver running > 10 seconds

### Preventing ANRs

**Main Thread**:
```kotlin
// ❌ BAD: Blocking operation on main thread
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val data = fetchFromNetwork()  // BLOCKS! ANR!
    displayData(data)
}

// ✅ GOOD: Launch coroutine
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    lifecycleScope.launch {
        val data = withContext(Dispatchers.IO) {
            fetchFromNetwork()  // Off main thread
        }
        displayData(data)  // Back on main thread
    }
}
```

**Binder Thread**:
```kotlin
// ❌ BAD: runBlocking in AIDL
override fun doWork(): String {
    return runBlocking {
        expensiveOperation()  // Blocks binder thread!
    }
}

// ✅ GOOD: Async with callback
override fun doWork(callback: IWorkCallback) {
    serviceScope.launch {
        val result = expensiveOperation()
        callback.onComplete(result)
    }
}
```

### ANR Detection and Monitoring

```kotlin
object AnrMonitor {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun startMonitoring() {
        watchMainThread()
    }

    private fun watchMainThread() {
        val postTime = SystemClock.elapsedRealtime()

        mainHandler.post {
            val delay = SystemClock.elapsedRealtime() - postTime

            if (delay > 5000) {
                Log.e("ANR", "Potential ANR! Main thread blocked for ${delay}ms")
                // Could also dump stack traces here
            }

            // Schedule next check
            mainHandler.postDelayed({ watchMainThread() }, 1000)
        }
    }
}
```

## Process vs Thread

### Columba's Multi-Process Architecture

```
Two Processes:
├─ App Process (com.lxmf.messenger)
│  ├─ Main thread (UI)
│  ├─ Coroutine dispatchers
│  └─ Binder client threads
│
└─ Service Process (com.lxmf.messenger:reticulum)
   ├─ Main thread (service lifecycle)
   ├─ Binder server threads (IPC handler)
   ├─ Python executor thread
   └─ Coroutine dispatchers
```

**Benefits**:
- Crash isolation (Python crash doesn't kill UI)
- Independent lifecycle (service survives app background)
- Memory isolation

**Costs**:
- IPC overhead (~3ms per call)
- Memory overhead (+30MB)
- Complexity (2000+ LOC for IPC)
- Cross-process race conditions

### When to Use Multi-Process

**Use Multi-Process When**:
- ✅ Need crash isolation
- ✅ Component must survive app lifecycle
- ✅ Want memory isolation
- ✅ Running unstable third-party code (Python/RNS)

**Use Single-Process When**:
- ✅ Simpler is better
- ✅ IPC overhead matters
- ✅ No crash isolation needed
- ✅ Components tightly coupled

**Columba Decision**: Keep multi-process for crash isolation (Phase 4.3 evaluation).

## Service Types

### Bound Service Pattern

```kotlin
class ReticulumService : Service() {
    private val binder = ReticulumBinder()

    inner class ReticulumBinder : Binder() {
        fun getService(): ReticulumService = this@ReticulumService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
}
```

**Threading**: All binder calls on binder threads, not main thread.

### Started Service Pattern

```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // Handle intent (on main thread)
    when (intent?.action) {
        ACTION_INITIALIZE -> {
            serviceScope.launch {
                initialize()
            }
        }
    }

    return START_STICKY
}
```

**Threading**: onStartCommand on main thread, launch work on coroutine.

## Best Practices Summary

### Main Thread Rules
- [ ] Keep callbacks < 5ms
- [ ] Never block main thread
- [ ] Monitor for frame drops
- [ ] Use coroutines for async work

### Binder Thread Rules
- [ ] Return immediately from AIDL methods (< 1ms)
- [ ] Use callbacks for async results
- [ ] Never use runBlocking in AIDL
- [ ] Don't exhaust binder thread pool

### Service Rules
- [ ] Keep lifecycle callbacks fast
- [ ] Use CoroutineScope for work
- [ ] Cancel scope in onDestroy
- [ ] Use foreground service for long work

### Thread Priority Rules
- [ ] Main/UI: DEFAULT priority
- [ ] Binder: FOREGROUND priority
- [ ] Background work: BACKGROUND priority
- [ ] Set priority explicitly

### ANR Prevention
- [ ] No blocking on main thread
- [ ] No blocking on binder threads
- [ ] Use timeouts for external operations
- [ ] Monitor main thread health

## Common Issues

### Issue: "Main thread blocked"

**Symptom**: Frame drops, slow UI

**Cause**: Long operation on main thread

**Solution**: Move to appropriate dispatcher
```kotlin
viewModelScope.launch {
    withContext(Dispatchers.IO) {
        heavyOperation()
    }
}
```

### Issue: ANR in service

**Symptom**: "Service not responding" dialog

**Cause**: runBlocking in AIDL or slow onCreate/onStartCommand

**Solution**: Use async with callbacks

### Issue: "Binder transaction too large"

**Symptom**: Exception when passing large data via IPC

**Cause**: Binder has 1MB transaction limit

**Solution**: Pass file path or use SharedMemory instead of data

### Issue: Service killed by system

**Symptom**: Service stops unexpectedly

**Cause**: System reclaims memory

**Solution**: Use foreground service with notification

## References

- **Android Developer Guide - Processes and Threads**: https://developer.android.com/guide/components/processes-and-threads
- **Android Developer Guide - Services**: https://developer.android.com/guide/components/services
- **THREADING_ARCHITECTURE_ANALYSIS.md**: Main thread Handler queue backup investigation

---

*Last Updated: 2025-10-27*
