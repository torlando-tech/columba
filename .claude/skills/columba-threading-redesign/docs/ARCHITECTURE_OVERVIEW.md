# Threading Architecture Overview

## Document Purpose

This document provides a comprehensive overview of the Columba Android app's threading architecture—both current state and target state after the 5-phase redesign.

## Current Architecture (Before Redesign)

### Process Model

```
┌─────────────────────────────┐      ┌──────────────────────────────┐
│   App Process (PID 9295)    │      │ Service Process (PID 9321)    │
├─────────────────────────────┤      ├───────────────────────────────┤
│ • ColumbaApplication         │      │ • ReticulumService            │
│ • ViewModels                 │      │ • Python/Chaquopy Runtime     │
│ • Compose UI                 │      │ • Reticulum Network Stack     │
│ • ServiceReticulumProtocol   │◄────►│ • IReticulumService (AIDL)    │
│   (IPC Client)               │ IPC  │   (Binder Implementation)     │
└─────────────────────────────┘      └───────────────────────────────┘
          │                                        │
          │                                        │
          ▼                                        ▼
    Main Thread                              Main Thread
    UI Thread Pool                           Binder Thread Pool
    Coroutine Dispatchers                    Coroutine Dispatchers
                                             Python GIL Thread
```

**Why Multi-Process?**
1. **Crash Isolation**: Python/RNS crash doesn't kill UI
2. **Independent Lifecycle**: Service can run independently
3. **Background Operation**: Service survives app backgrounding

**Trade-offs**:
- IPC overhead: ~3ms per call
- Memory overhead: +30MB for separate process
- Complexity: 2000+ LOC for IPC layer
- Race conditions: Cross-process state synchronization

### Current Threading Issues

#### 1. Handler.post() Bottleneck (FIXED)

**Original Issue** (THREADING_ARCHITECTURE_ANALYSIS.md:20-60):
```kotlin
// ReticulumService.kt (before fix)
val latch = CountDownLatch(1)
val resultRef = AtomicReference<PyObject>()

Handler(Looper.getMainLooper()).post {
    resultRef.set(wrapper!!.callAttr("initialize", configJson))
    latch.countDown()
}

// Main thread was so busy that this task sat in queue for 60+ seconds!
latch.await(60, TimeUnit.SECONDS)  // Timeout!
```

**Timeline from logs**:
```
18:44:57.798 - Timeout reached (60s wait)
18:44:57.799 - Python initialize() FINALLY called (1ms later!)
18:45:00.083 - Python initialization completed (2.3s total)
```

**Root Cause**:
- Service process main thread Handler queue severely backed up
- Task queued for 60+ seconds before execution
- Python initialization itself only takes ~2 seconds
- The wait, not the work, was the problem

**Fix Applied** (commit b8fdbb2):
```kotlin
// Call directly on coroutine thread
val result = wrapper!!.callAttr("initialize", configJson)
```

**Result**: Initialization time 60s → 2s ✅

#### 2. Blocking Binder Threads

**Current Code** (ReticulumService.kt:85):
```kotlin
override fun initialize(configJson: String): String {
    return runBlocking {  // ❌ Blocks binder thread!
        serviceScope.async {
            // Long-running operation...
        }.await()
    }
}
```

**Problem**:
- AIDL methods execute on limited binder thread pool (~16 threads)
- `runBlocking` blocks these threads waiting for results
- Can exhaust thread pool → ANR (Application Not Responding)
- Binder threads have ~5 second timeout

**Impact**:
- Potential ANR errors
- IPC becomes unreliable under load
- Other IPC calls blocked waiting for thread

**Must Fix**: Phase 1.2

#### 3. Polling Loops Everywhere

**Pattern Found Throughout**:
```kotlin
// ServiceReticulumProtocol.kt:247-278
suspend fun waitForReady(timeoutMillis: Long = 10000): Boolean {
    var attempts = 0
    val maxAttempts = (timeoutMillis / 100).toInt()

    while (attempts < maxAttempts) {
        when (val status = getStatus()) {
            is NetworkStatus.READY -> return true
            else -> delay(100)  // ❌ Polling every 100ms
        }
        attempts++
    }
    return false
}

// Example: Polling pattern (removed)
private fun startAnnouncesPolling() {
    pollingJob?.cancel()
    pollingJob = pollingScope.launch {
        while (isActive) {
            try {
                val announces = wrapper!!.callAttr("get_announces")
                // Process announces...
            } catch (e: Exception) { }
            delay(2.seconds)  // ❌ Polling every 2 seconds
        }
    }
}
```

**Problems**:
1. **CPU waste**: Wakes up periodically even when idle
2. **Battery drain**: Continuous polling in background
3. **Latency**: Up to polling interval delay for updates
4. **Arbitrary values**: delay(100ms), delay(2s) - why those values?

**Impact**:
- Idle CPU usage unnecessarily high
- Battery life reduced
- User experience: delayed updates

**Must Fix**: Phase 2

#### 4. Arbitrary Delays as Race Workarounds

**Examples**:
```kotlin
// ServiceReticulumProtocol.kt:181-195
suspend fun bindService(): Boolean {
    // ... binding logic ...
    delay(500)  // ❌ "Hope it's ready now?"
    return true
}

// Various locations
delay(100)  // Wait for status update?
delay(500)  // Wait for service ready?
delay(1000) // Wait for initialization?
```

**Problem**: No deterministic signals, just "wait and hope"

**Must Fix**: Phase 2.3

#### 5. Cross-Process Race Conditions

**Initialization Race** (THREADING_ARCHITECTURE_ANALYSIS.md:100-116):
```
// Logs show BOTH processes trying to initialize:
10-27 18:42:19.361  5009  5009  ColumbaApplication: Auto-initializing Reticulum...
10-27 18:42:19.757  4989  4989  ColumbaApplication: Auto-initializing Reticulum...
```

**Problem**:
- No clear ownership of initialization
- Both app process AND service process attempt init
- Timing-dependent: one succeeds, one fails
- Race condition on first launch

**Impact**:
- Unreliable initialization
- Duplicate work
- Potential corruption if both partially succeed

**Must Fix**: Phase 4.1

### Current Dispatcher Usage

**Found in Codebase**:

```kotlin
// ReticulumService.kt:77
private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

// PythonReticulumProtocol.kt:67
private val pollingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

// Mixed usage throughout:
launch(Dispatchers.Main) { /* sometimes */ }
launch(Dispatchers.IO) { /* sometimes */ }
launch(Dispatchers.Default) { /* sometimes */ }
launch { /* no dispatcher specified - uses scope default */ }
```

**Problems**:
1. No consistent strategy
2. Python calls on mixed dispatchers
3. Unclear why each dispatcher chosen
4. No documentation of threading contracts

**Must Fix**: Phase 3.2

---

## Target Architecture (After Redesign)

### Threading Model

```
┌─────────────────────────────────────────────────────────────────────┐
│                        App Process (UI)                              │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  Main Thread                                                         │
│  ├─ Activity/Fragment lifecycle                                     │
│  ├─ Compose recomposition                                           │
│  └─ UI updates only (<16ms operations)                              │
│                                                                      │
│  ViewModelScope (Dispatchers.Main.immediate)                        │
│  ├─ StateFlow emissions                                             │
│  ├─ UI state updates                                                │
│  └─ Collect from service Flows                                      │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │  IPC Layer (ServiceReticulumProtocol)                          │ │
│  │  • Async callbacks only (no blocking)                          │ │
│  │  • Flow-based subscriptions                                    │ │
│  │  • < 10ms latency target                                       │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
                                ▲
                                │ IPC (async only)
                                ▼
┌──────────────────────────────────────────────────────────────────────┐
│                    Service Process (Background)                      │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  Main Thread                                                         │
│  ├─ Service lifecycle only                                          │
│  ├─ IPC binder calls (must return immediately)                     │
│  └─ NO blocking operations (ever!)                                  │
│                                                                      │
│  Python Executor Thread (Single-threaded, THREAD_PRIORITY_DEFAULT)  │
│  ├─ ALL Python/Chaquopy calls                                       │
│  ├─ Sequential execution (GIL serializes anyway)                    │
│  ├─ Queue monitoring                                                │
│  └─ 30s timeout per operation                                       │
│                                                                      │
│  IO Dispatcher Pool                                                  │
│  ├─ Database operations (Room)                                      │
│  ├─ File I/O                                                        │
│  └─ Network operations (if any)                                     │
│                                                                      │
│  Default Dispatcher Pool                                            │
│  ├─ JSON parsing                                                    │
│  ├─ Encryption/decryption                                           │
│  └─ CPU-intensive work                                              │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │  State Management                                               │ │
│  │  • StateFlow<NetworkStatus>                                     │ │
│  │  • SharedFlow<AnnounceEvent>                                    │ │
│  │  • SharedFlow<MessageEvent>                                     │ │
│  │  • Event-driven, no polling                                     │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

### Key Architectural Principles

#### 1. Event-Driven, Not Polling

**Before**:
```kotlin
while (true) {
    checkForUpdates()
    delay(interval)
}
```

**After**:
```kotlin
statusFlow
    .filter { it is NetworkStatus.READY }
    .collect { startWork() }
```

**Benefits**:
- Instant propagation (< 10ms)
- Zero CPU when idle
- Better battery life
- Deterministic behavior

#### 2. Async IPC with Callbacks

**Before**:
```kotlin
override fun doWork(): String {
    return runBlocking { /* work */ }  // Blocks binder thread
}
```

**After**:
```kotlin
override fun doWork(callback: IWorkCallback) {
    serviceScope.launch {
        val result = performWork()
        callback.onComplete(result)
    }
}
```

**Benefits**:
- Binder threads never block
- No ANR risk
- Concurrent operations possible
- Better error handling

#### 3. Single-Threaded Python Executor

**Implementation**:
```kotlin
object PythonExecutor {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PythonWorker")
    }
    private val dispatcher = executor.asCoroutineDispatcher()

    suspend fun <T> execute(block: () -> T): T =
        withTimeout(30_000) {
            withContext(dispatcher) {
                block()
            }
        }
}
```

**Benefits**:
- Predictable execution order
- Easy queue monitoring
- Timeout enforcement
- Prevents dispatcher exhaustion
- Simpler debugging

#### 4. Clear Dispatcher Strategy

| Work Type | Dispatcher | Reasoning |
|-----------|-----------|-----------|
| Python calls | PythonExecutor (single-threaded) | Predictable ordering, queue monitoring |
| Database (Room) | Automatic (Room uses IO) | Room handles threading |
| File I/O | Dispatchers.IO | Blocking I/O operations |
| Network | Dispatchers.IO | Blocking I/O operations |
| CPU work | Dispatchers.Default | Parallel CPU-bound work |
| UI updates | Dispatchers.Main | Must be on main thread |

#### 5. StateFlow for Status Distribution

**Implementation**:
```kotlin
// Service emits
private val _networkStatus = MutableStateFlow<NetworkStatus>(NetworkStatus.SHUTDOWN)
val networkStatus: StateFlow<NetworkStatus> = _networkStatus.asStateFlow()

// Update when state changes
private fun updateStatus(newStatus: NetworkStatus) {
    _networkStatus.value = newStatus
}

// UI collects
viewModelScope.launch {
    protocol.networkStatus.collect { status ->
        _uiState.value = when (status) {
            NetworkStatus.READY -> UiState.Connected
            NetworkStatus.INITIALIZING -> UiState.Connecting
            // ...
        }
    }
}
```

**Benefits**:
- Type-safe status representation
- Automatic UI updates
- Always has current value
- Replay for new collectors
- Thread-safe

### Performance Targets

All operations measured and enforced:

| Metric | Current | Target | How to Measure |
|--------|---------|--------|----------------|
| Initialization | ~2s ✅ | < 3s | Instrument Python wrapper init |
| IPC round-trip | Unknown | < 10ms | Add timing to AIDL calls |
| Status propagation | ~100ms (polling) | < 10ms | StateFlow emission timing |
| Message delivery (active) | ~2s (polling) | < 500ms | Smart polling + callbacks |
| Message delivery (idle) | ~2s (polling) | < 30s | Exponential backoff |
| CPU usage (idle) | ~5% | < 1% | Android Profiler |
| Battery (24h) | Unknown | < 2% | Battery Historian |
| Frame time | Unknown | < 16ms | Choreographer monitoring |

### Testing Strategy

Comprehensive test coverage at multiple levels:

```
Unit Tests (90%+ coverage target)
├─ Python executor queue behavior
├─ StateFlow state transitions
├─ Dispatcher usage verification
└─ Timeout handling

Integration Tests
├─ IPC communication (app ↔ service)
├─ Cross-process state synchronization
├─ Service lifecycle scenarios
└─ Error recovery paths

Threading Safety Tests
├─ Concurrent Python calls (10+ simultaneous)
├─ Rapid invocations (1000+ calls)
├─ Thread leak detection (24-hour run)
└─ Race condition detection

Performance Tests
├─ Initialization timing
├─ IPC latency measurement
├─ Status propagation timing
├─ CPU usage profiling
└─ Battery impact measurement

Stress Tests
├─ Long-running operations
├─ High-frequency IPC calls
├─ Python executor queue backup
└─ Memory pressure scenarios
```

### Monitoring and Observability

Real-time metrics collected:

```kotlin
object ThreadingMetrics {
    // Performance
    val initializationTime = Metric("init_ms", target = 3000)
    val ipcLatency = Metric("ipc_ms", target = 10)
    val statusPropagation = Metric("status_ms", target = 10)

    // Threading health
    val mainThreadBlockTime = Metric("main_block_ms", target = 16)
    val pythonQueueDepth = Metric("python_queue", target = 3)
    val droppedFrames = Metric("frames_dropped", target = 0)

    // Resource usage
    val cpuUsageIdle = Metric("cpu_idle_pct", target = 1)
    val batteryDrain24h = Metric("battery_pct", target = 2)
    val threadCount = Metric("threads", target = 30)

    // Reliability
    val anrCount = Metric("anr_count", target = 0)
    val crashCount = Metric("crashes", target = 0)
    val threadLeaks = Metric("thread_leaks", target = 0)
}
```

---

## Migration Path

### Phase-by-Phase Transformation

**Phase 1: Foundation** (Week 1)
- Verify Python threading safety
- Remove blocking from IPC
- Fix transaction nesting
- **Result**: Stable foundation for further work

**Phase 2: Events** (Week 2)
- Status → StateFlow
- Smart polling for announces/messages
- Explicit service readiness
- **Result**: Reactive, efficient communication

**Phase 3: Threading** (Week 3)
- Python executor
- Dispatcher audit
- Main thread freed
- **Result**: Proper threading architecture

**Phase 4: IPC** (Week 4)
- Clear ownership
- Message versioning
- Architecture evaluation
- **Result**: Simplified, reliable IPC

**Phase 5: Testing** (Ongoing)
- Comprehensive tests
- Performance monitoring
- Documentation
- **Result**: Maintainable, observable system

### Risk Mitigation

**High-Risk Changes**:
1. Removing runBlocking (could break IPC) → **Mitigation**: Extensive testing, gradual rollout
2. Converting polling to Flows (could miss events) → **Mitigation**: Verify all events captured
3. Python executor (could serialize unnecessarily) → **Mitigation**: Measure performance impact

**Rollback Strategy**:
- Keep old code paths behind feature flag
- Can revert individual phases
- Comprehensive logging for diagnosis
- Beta testing before production

---

## Decision Log

### Multi-Process Architecture (Decided: Keep)

**Date**: 2025-10-27
**Decision**: Keep two-process architecture
**Rationale**:
- Crash isolation valuable (Python crashes common during development)
- Independent lifecycle useful for background operation
- IPC overhead acceptable (~3ms) for benefits

**Alternatives Considered**:
- Single-process: Simpler but no isolation
- Hybrid: Complex, no clear benefit

### Python Threading Model (Decided: Single-Threaded Executor)

**Date**: 2025-10-27
**Decision**: Use single-threaded executor for all Python calls
**Rationale**:
- GIL serializes anyway
- Predictable ordering
- Queue monitoring capability
- Simpler debugging

**Alternatives Considered**:
- IO dispatcher: Works but less predictable
- Multiple threads: No benefit due to GIL

### Status Distribution (Decided: StateFlow)

**Date**: 2025-10-27
**Decision**: Use StateFlow<NetworkStatus> for status
**Rationale**:
- Type-safe
- Automatic UI updates
- Always has current value
- Thread-safe

**Alternatives Considered**:
- Callbacks: More boilerplate
- Polling: Inefficient (current problem)

---

## References

- **THREADING_REDESIGN_PLAN.md**: Detailed 5-phase implementation plan
- **THREADING_ARCHITECTURE_ANALYSIS.md**: Problem discovery and root cause analysis
- **ReticulumService.kt**: Current service implementation
- **ServiceReticulumProtocol.kt**: Current IPC layer implementation

---

*Last Updated: 2025-10-27*
*Next Review: After Phase 1 Completion*
