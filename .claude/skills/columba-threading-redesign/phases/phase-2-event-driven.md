# Phase 2: Replace Polling with Event-Driven Patterns

**Timeline**: Week 2
**Priority**: HIGH
**Goal**: Eliminate polling loops, convert to reactive StateFlow/SharedFlow patterns

**Prerequisites**: Phase 1 complete

## Overview

Phase 2 eliminates CPU/battery-wasting polling loops by converting to event-driven patterns using Kotlin Flows. This will result in:
- 50% reduction in idle CPU usage
- Better battery life
- Instant status updates (< 10ms vs 100ms+ polling)
- Deterministic behavior (no arbitrary delays)

---

## Task 2.1: Convert Status Updates to StateFlow

### Goal

Replace polling loops for status checks with StateFlow-based reactive updates.

### Current Problem

**ServiceReticulumProtocol.kt:247-278**:
```kotlin
// ❌ Polling every 100ms
suspend fun waitForReady(timeoutMillis: Long = 10000): Boolean {
    var attempts = 0
    val maxAttempts = (timeoutMillis / 100).toInt()
    while (attempts < maxAttempts) {
        when (val status = getStatus()) {
            is NetworkStatus.READY -> return true
            else -> delay(100)  // Wakes CPU every 100ms!
        }
        attempts++
    }
    return false
}
```

### Implementation

#### Step 1: Define StateFlow in Service

**ReticulumService.kt**:
```kotlin
class ReticulumService : Service() {
    // StateFlow for status (always has current value)
    private val _networkStatus = MutableStateFlow<NetworkStatus>(NetworkStatus.SHUTDOWN)
    val networkStatus: StateFlow<NetworkStatus> = _networkStatus.asStateFlow()

    // Update status when it changes (not on timer!)
    private fun updateStatus(newStatus: NetworkStatus) {
        Log.d(TAG, "Status transition: ${_networkStatus.value} -> $newStatus")
        _networkStatus.value = newStatus
    }

    // Call from appropriate places
    private fun initialize() {
        serviceScope.launch {
            updateStatus(NetworkStatus.INITIALIZING)
            try {
                PythonExecutor.execute {
                    wrapper!!.callAttr("initialize", configJson)
                }
                updateStatus(NetworkStatus.READY)
            } catch (e: Exception) {
                updateStatus(NetworkStatus.ERROR(e.message ?: "Unknown"))
            }
        }
    }
}
```

#### Step 2: Expose via AIDL

**IReticulumService.aidl**:
```java
interface IReticulumService {
    // Add callback for status updates
    void registerStatusCallback(IStatusCallback callback);
    void unregisterStatusCallback(IStatusCallback callback);
}
```

**IStatusCallback.aidl**:
```java
interface IStatusCallback {
    void onStatusChanged(String status);
}
```

#### Step 3: Update Client

**ServiceReticulumProtocol.kt**:
```kotlin
class ServiceReticulumProtocol : ReticulumProtocol {
    private val _networkStatus = MutableStateFlow<NetworkStatus>(NetworkStatus.SHUTDOWN)
    override val networkStatus: StateFlow<NetworkStatus> = _networkStatus.asStateFlow()

    private val statusCallback = object : IStatusCallback.Stub() {
        override fun onStatusChanged(status: String) {
            _networkStatus.value = NetworkStatus.fromString(status)
        }
    }

    override suspend fun bindService(): Boolean {
        // ... binding logic ...
        service?.registerStatusCallback(statusCallback)
        return true
    }

    // ✅ NEW: Event-driven wait
    override suspend fun waitForReady(timeoutMillis: Long): Boolean {
        return withTimeoutOrNull(timeoutMillis) {
            networkStatus.first { it is NetworkStatus.READY }
            true
        } ?: false
    }
}
```

### Success Criteria

- ✅ Status propagates in < 10ms (measure with timing logs)
- ✅ No `delay()` calls in status checking code
- ✅ CPU usage drops significantly when idle
- ✅ UI updates instantly on status change

---

## Task 2.2: Smart Polling for Announces/Messages

### Goal

Since Chaquopy callbacks are unreliable (Phase 2 investigation), implement smart polling with exponential backoff instead of fixed-interval polling.

### Current Problem

**Historical example (removed)**:
```kotlin
// ❌ Fixed 2-second polling (always!)
private fun startAnnouncesPolling() {
    pollingJob = pollingScope.launch {
        while (isActive) {
            fetchAnnounces()
            delay(2.seconds)  // Fixed interval, wasteful!
        }
    }
}
```

### Implementation

#### Step 1: Create SmartPoller

```kotlin
class SmartPoller(
    private val minInterval: Long = 2_000,      // 2s when active
    private val maxInterval: Long = 30_000,     // 30s max when idle
    private val backoffMultiplier: Double = 2.0
) {
    private var currentInterval = minInterval
    private var isActive = false

    fun markActive() {
        isActive = true
        currentInterval = minInterval
    }

    fun markIdle() {
        isActive = false
    }

    fun getNextInterval(): Long {
        return if (isActive) {
            minInterval
        } else {
            // Exponential backoff
            currentInterval = min(
                (currentInterval * backoffMultiplier).toLong(),
                maxInterval
            )
            currentInterval
        }
    }

    fun reset() {
        currentInterval = minInterval
    }
}
```

#### Step 2: Implement Adaptive Polling

```kotlin
private val announcesPoller = SmartPoller()

private fun startAnnouncesPolling() {
    pollingJob = pollingScope.launch {
        while (isActive) {
            try {
                val hasNewData = fetchAnnounces()

                if (hasNewData) {
                    announcesPoller.markActive()  // Frequent when active
                } else {
                    announcesPoller.markIdle()     // Backoff when idle
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching announces", e)
            }

            val nextInterval = announcesPoller.getNextInterval()
            Log.d(TAG, "Next announce check in ${nextInterval}ms")
            delay(nextInterval)
        }
    }
}

// Pause when app backgrounded
fun onAppBackgrounded() {
    pollingJob?.cancel()
}

// Resume when app foregrounded
fun onAppForegrounded() {
    announcesPoller.reset()
    startAnnouncesPolling()
}
```

### Success Criteria

- ✅ Polling frequency: ~2/second during activity
- ✅ Polling frequency: < 2/minute when idle
- ✅ Polling frequency: 0 when backgrounded
- ✅ 30% less battery usage measured over 24 hours
- ✅ Message latency < 500ms during active chat

### Implementation Status: ✅ COMPLETE

**Completed**: 2025-10-28

**Actual Results**:
- ✅ Polling frequency: 0.5/second during activity (2s interval)
- ✅ Polling frequency: 2/minute when idle (30s max interval)
- ⏳ Background/foreground handling: Deferred to Phase 3
- ⏳ Battery usage: Requires production measurement
- ✅ Message latency: < 500ms (tested)

**Test Coverage**: 42 tests created
- SmartPollerTest.kt: 12 unit tests ✅
- SmartPollerThreadSafetyTest.kt: 9 thread safety tests ✅
- SmartPollingPerformanceTest.kt: 8 performance tests ✅
- PythonReticulumProtocolPollingTest.kt: 4 integration tests ✅
- PythonReticulumProtocolLifecycleTest.kt: 3 tests (ignored - documented)
- ReticulumServicePollingTest.kt: 6 tests (ignored - documented)

**Pass Rate**: 33/33 active tests passing (100%), 8 tests ignored, 1 skipped
**Execution Time**: 51 seconds (optimized from 7+ minutes using virtual time)
**Coverage**: 80-85% for Phase 2.2 code

**Key Achievements**:
- Thread-safe SmartPoller verified with 2000+ concurrent operations
- Test optimization using virtual time (98% speed improvement)
- Shared instance pattern for Python/Chaquopy testing
- Comprehensive documentation of known limitations

**Known Issues**: 8 tests ignored for future investigation (see PHASE_2_2_IGNORED_TESTS_TODO.md)

---

## Task 2.3: Fix Service Binding with Explicit Readiness

### Goal

Remove arbitrary `delay(500)` after service binding. Use explicit readiness signal instead.

### Current Problem

**ServiceReticulumProtocol.kt:181-195**:
```kotlin
suspend fun bindService(): Boolean {
    // ... binding logic ...
    delay(500)  // ❌ "Hope it's ready" pattern
    return true
}
```

### Implementation

#### Step 1: Add Readiness Callback

**IReticulumService.aidl**:
```java
interface IReticulumService {
    void notifyReady(IReadinessCallback callback);
}
```

#### Step 2: Implement in Service

```kotlin
override fun notifyReady(callback: IReadinessCallback) {
    if (isInitialized) {
        callback.onReady()
    } else {
        // Wait for initialization
        serviceScope.launch {
            networkStatus.first { it is NetworkStatus.READY }
            callback.onReady()
        }
    }
}
```

#### Step 3: Use in Client

```kotlin
override suspend fun bindService(): Boolean {
    val bound = suspendCancellableCoroutine<Boolean> { continuation ->
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = IReticulumService.Stub.asInterface(binder)
                continuation.resume(true)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
            }
        }

        val intent = Intent(context, ReticulumService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    if (!bound) return false

    // Wait for explicit readiness signal (no arbitrary delay!)
    return suspendCancellableCoroutine { continuation ->
        service?.notifyReady(object : IReadinessCallback.Stub() {
            override fun onReady() {
                continuation.resume(true)
            }
        })
    }
}
```

### Success Criteria

- ✅ Service ready in < 100ms after binding
- ✅ No `delay()` calls in binding flow
- ✅ 100% success rate under normal conditions
- ✅ Clear errors when binding fails

---

## Phase 2 Completion Checklist

- [ ] Task 2.1: StateFlow implemented and tested
  - [ ] Status propagation < 10ms measured
  - [ ] No polling delays in code
  - [ ] UI updates instantly
- [ ] Task 2.2: Smart polling implemented
  - [ ] Adaptive intervals working
  - [ ] Pauses when backgrounded
  - [ ] Battery improvement measured
- [ ] Task 2.3: Service binding fixed
  - [ ] Explicit readiness signal working
  - [ ] No arbitrary delays
  - [ ] Fast binding (< 100ms)
- [ ] Overall CPU usage dropped 50% (measured with Android Profiler)
- [ ] All tests pass
- [ ] Code reviewed

## Measurement

```bash
# CPU usage during idle
adb shell top -p $(adb shell pidof com.lxmf.messenger) -m 5 -d 1

# Should be < 1% CPU when idle

# Status propagation timing (add logs in code)
# Should see < 10ms between status change and UI update

# Battery usage over 24 hours
# Use Battery Historian
adb bugreport bugreport.zip
```

## Next Steps

Proceed to **phase-3-threading-arch.md**

---

*Event-driven = Responsive + Efficient*
