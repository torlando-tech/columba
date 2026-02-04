# Phase 4: Simplify Cross-Process Communication

**Timeline**: Week 4
**Priority**: MEDIUM
**Goal**: Optimize IPC patterns, reduce cross-process complexity

**Prerequisites**: Phases 1-3 complete

## Overview

Phase 4 simplifies cross-process communication by:
1. Defining clear initialization ownership (no more races)
2. Adding version/sequence numbers to IPC messages
3. Evaluating whether multi-process architecture is still necessary

---

## Task 4.1: Define Clear Initialization Ownership

### Goal

Designate service process as sole initializer, eliminate cross-process race conditions.

### Current Problem

**From THREADING_ARCHITECTURE_ANALYSIS.md:100-116**:
```
Both app process AND service process try to initialize:
10-27 18:42:19.361  5009  5009  ColumbaApplication: Auto-initializing Reticulum...
10-27 18:42:19.757  4989  4989  ColumbaApplication: Auto-initializing Reticulum...
```

One fails with "Service not bound", timing-dependent behavior.

### Implementation

#### Step 1: Remove Initialization from App Process

**ColumbaApplication.kt**:
```kotlin
class ColumbaApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // ❌ OLD: App tries to initialize
        // if (shouldAutoInit) {
        //     protocol.initialize()
        // }

        // ✅ NEW: Only observe status
        lifecycleScope.launch {
            protocol.networkStatus.collect { status ->
                when (status) {
                    NetworkStatus.READY -> onNetworkReady()
                    is NetworkStatus.ERROR -> onNetworkError(status)
                    else -> {}
                }
            }
        }
    }
}
```

#### Step 2: Service as Sole Initializer

**ReticulumService.kt**:
```kotlin
class ReticulumService : Service() {
    override fun onCreate() {
        super.onCreate()

        // Service ALWAYS initializes (it owns initialization)
        serviceScope.launch {
            initializeReticulum()
        }
    }

    private suspend fun initializeReticulum() {
        if (_networkStatus.value != NetworkStatus.SHUTDOWN) {
            Log.w(TAG, "Already initialized, skipping")
            return
        }

        updateStatus(NetworkStatus.INITIALIZING)
        try {
            PythonExecutor.execute("initialize") {
                wrapper!!.callAttr("initialize", configJson)
            }
            updateStatus(NetworkStatus.READY)
        } catch (e: Exception) {
            updateStatus(NetworkStatus.ERROR(e.message ?: "Unknown"))
        }
    }
}
```

#### Step 3: Add State Machine

```kotlin
sealed class NetworkStatus {
    object SHUTDOWN : NetworkStatus()
    object INITIALIZING : NetworkStatus()
    object READY : NetworkStatus()
    data class ERROR(val message: String) : NetworkStatus()

    // State transitions validation
    fun canTransitionTo(newStatus: NetworkStatus): Boolean {
        return when (this) {
            SHUTDOWN -> newStatus is INITIALIZING
            INITIALIZING -> newStatus is READY || newStatus is ERROR
            READY -> newStatus is SHUTDOWN  // Allow shutdown
            is ERROR -> newStatus is INITIALIZING || newStatus is SHUTDOWN  // Allow retry
        }
    }
}

// Use in updateStatus
private fun updateStatus(newStatus: NetworkStatus) {
    if (!_networkStatus.value.canTransitionTo(newStatus)) {
        Log.w(TAG, "Invalid state transition: ${_networkStatus.value} -> $newStatus")
        return
    }
    _networkStatus.value = newStatus
}
```

### Success Criteria

- ✅ Only service process calls initialize()
- ✅ App process only observes status
- ✅ No "Service not bound" errors
- ✅ State machine prevents invalid transitions
- ✅ No initialization race conditions

---

## Task 4.2: Improve IPC Patterns

### Goal

Add version numbers, sequence numbers, better error handling to IPC messages.

### Implementation

#### Step 1: Add Message Wrapper

```kotlin
data class IpcMessage<T>(
    val version: Int = 1,           // Protocol version
    val sequence: Long,              // Monotonic sequence number
    val timestamp: Long = System.currentTimeMillis(),
    val payload: T
)

object IpcMessageFactory {
    private val sequenceCounter = AtomicLong(0)

    fun <T> create(payload: T): IpcMessage<T> {
        return IpcMessage(
            sequence = sequenceCounter.incrementAndGet(),
            payload = payload
        )
    }
}
```

#### Step 2: Update AIDL to Use Wrappers

**IReticulumService.aidl**:
```java
interface IReticulumService {
    // Add version and sequence to messages
    void initializeV2(String messageJson, IInitializationCallback callback);
    // messageJson contains serialized IpcMessage<InitConfig>
}
```

#### Step 3: Add IPC Logging

```kotlin
object IpcLogger {
    fun logCall(method: String, message: IpcMessage<*>) {
        Log.d("IPC", "→ $method seq=${message.sequence} v=${message.version}")
    }

    fun logResponse(method: String, message: IpcMessage<*>) {
        Log.d("IPC", "← $method seq=${message.sequence} v=${message.version}")
    }
}
```

#### Step 4: Verify Message Ordering

```kotlin
class IpcMessageValidator {
    private var lastSequence = 0L

    fun validate(message: IpcMessage<*>): Boolean {
        if (message.sequence <= lastSequence) {
            Log.w("IPC", "Out-of-order message: ${message.sequence} <= $lastSequence")
            return false
        }
        lastSequence = message.sequence
        return true
    }
}
```

### Success Criteria

- ✅ All IPC messages have sequence numbers
- ✅ Version numbers allow future protocol changes
- ✅ Message ordering verified
- ✅ Complete IPC trace in logs
- ✅ IPC latency < 10ms consistently

---

## Task 4.3: Evaluate Architecture Simplification

### Goal

Make data-driven decision: Keep multi-process or move to single-process?

### Implementation

#### Step 1: Document Current Benefits

Create document comparing architectures:

```markdown
## Multi-Process Architecture Evaluation

### Current Benefits
- Crash isolation: Python crash doesn't kill UI ✅
- Independent lifecycle: Service survives app background ✅
- Memory isolation: Separate heaps ✅

### Current Costs
- IPC overhead: ~3ms per call (measured)
- Memory overhead: +30MB for second process
- Code complexity: 2000+ LOC for IPC layer
- Cross-process races: Initialization, status sync

### Single-Process Alternative

Benefits:
- Simpler: No IPC layer needed
- Faster: Direct method calls (0ms overhead)
- Less memory: Single process
- No cross-process races

Costs:
- No crash isolation: Python crash kills app
- Shared lifecycle: App background kills Python
- Shared memory: One OOM kills both
```

#### Step 2: Measure IPC Overhead

```kotlin
object IpcMetrics {
    fun measureRoundTrip() {
        val start = SystemClock.elapsedRealtimeNanos()
        protocol.ping()  // Simple IPC call
        val duration = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
        Log.i("Metrics", "IPC round-trip: ${duration}ms")
    }
}
```

#### Step 3: Count LOC for IPC

```bash
# Count lines of IPC-related code
find app/src/main -name "*.aidl" -o -name "*Protocol.kt" | xargs wc -l
```

#### Step 4: Make Decision

Create decision matrix:

| Factor | Multi-Process | Single-Process | Weight | Score |
|--------|--------------|----------------|--------|-------|
| Crash Safety | 10 | 0 | High | MP wins |
| Performance | 7 | 10 | Medium | SP wins |
| Complexity | 3 | 10 | High | SP wins |
| Memory | 5 | 10 | Low | SP wins |
| Reliability | 6 | 8 | High | SP wins |

**Recommendation**: Document decision with rationale

### Success Criteria

- ✅ Architecture comparison documented
- ✅ IPC overhead measured (expect 2-5ms)
- ✅ Code complexity counted
- ✅ Decision matrix created
- ✅ Team consensus on direction

---

## Phase 4 Completion Checklist

- [ ] Task 4.1: Clear initialization ownership
  - [ ] Only service initializes
  - [ ] App only observes
  - [ ] State machine implemented
  - [ ] No races observed
- [ ] Task 4.2: IPC improvements
  - [ ] Sequence numbers added
  - [ ] Version numbers added
  - [ ] Logging complete
  - [ ] Ordering verified
- [ ] Task 4.3: Architecture evaluated
  - [ ] Comparison document created
  - [ ] Metrics collected
  - [ ] Decision made and documented
- [ ] IPC latency < 10ms verified
- [ ] All tests pass
- [ ] Code reviewed

## Measurement

```bash
# Check for initialization races
adb logcat | grep -i "initializing reticulum"
# Should only see from service process

# Measure IPC latency
adb logcat | grep "IPC round-trip"
# Should be < 10ms consistently

# IPC message ordering
adb logcat | grep "IPC.*seq="
# Sequences should be monotonic increasing
```

## Next Steps

Proceed to **phase-5-testing.md**

---

*Simplicity = Reliability*
