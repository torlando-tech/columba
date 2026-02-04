# Phase 5: Add Monitoring and Testing

**Timeline**: Ongoing (throughout and after Phases 1-4)
**Priority**: HIGH
**Goal**: Comprehensive test coverage and performance monitoring

**Prerequisites**: Phases 1-4 complete (or in progress)

## Overview

Phase 5 ensures long-term reliability through:
1. Performance metrics and monitoring
2. Comprehensive threading test suite
3. Documentation of threading architecture

**Note**: Start Phase 5 tasks early! Add tests as you implement each phase.

---

## Task 5.1: Performance Metrics

### Goal

Instrument all critical paths, create monitoring dashboard, set up CI performance tests.

### Implementation

#### Step 1: Create Metrics Framework

**File**: `app/src/main/java/com/lxmf/messenger/metrics/PerformanceMetrics.kt`

```kotlin
package com.lxmf.messenger.metrics

import android.util.Log
import kotlin.system.measureTimeMillis

data class Metric(
    val name: String,
    val targetMs: Long,
    private val measurements: MutableList<Long> = mutableListOf()
) {
    fun record(durationMs: Long) {
        measurements.add(durationMs)

        if (durationMs > targetMs) {
            Log.w("Metrics", "⚠️ $name exceeded target: ${durationMs}ms > ${targetMs}ms")
        } else {
            Log.d("Metrics", "✓ $name within target: ${durationMs}ms")
        }
    }

    fun getStats(): MetricStats {
        return MetricStats(
            name = name,
            count = measurements.size,
            average = measurements.average(),
            min = measurements.minOrNull() ?: 0,
            max = measurements.maxOrNull() ?: 0,
            withinTarget = measurements.count { it <= targetMs },
            exceedingTarget = measurements.count { it > targetMs }
        )
    }
}

data class MetricStats(
    val name: String,
    val count: Int,
    val average: Double,
    val min: Long,
    val max: Long,
    val withinTarget: Int,
    val exceedingTarget: Int
)

object PerformanceMetrics {
    // Define all metrics with targets
    val initializationTime = Metric("initialization", targetMs = 3000)
    val ipcRoundTrip = Metric("ipc_latency", targetMs = 10)
    val statusPropagation = Metric("status_propagation", targetMs = 10)
    val pythonCallTime = Metric("python_call", targetMs = 100)
    val messageDelivery = Metric("message_delivery", targetMs = 500)

    private val allMetrics = listOf(
        initializationTime,
        ipcRoundTrip,
        statusPropagation,
        pythonCallTime,
        messageDelivery
    )

    fun getAllStats(): List<MetricStats> {
        return allMetrics.map { it.getStats() }
    }

    fun assertAllWithinTarget() {
        allMetrics.forEach { metric ->
            val stats = metric.getStats()
            check(stats.exceedingTarget == 0) {
                "${metric.name}: ${stats.exceedingTarget} measurements exceeded target"
            }
        }
    }
}

// Helper function
inline fun <T> measureAndRecord(metric: Metric, block: () -> T): T {
    var result: T
    val duration = measureTimeMillis {
        result = block()
    }
    metric.record(duration)
    return result
}
```

#### Step 2: Instrument Critical Paths

```kotlin
// Initialization
suspend fun initialize() {
    measureAndRecord(PerformanceMetrics.initializationTime) {
        PythonExecutor.execute("initialize") {
            wrapper.callAttr("initialize", configJson)
        }
    }
}

// IPC calls
suspend fun ping() {
    measureAndRecord(PerformanceMetrics.ipcRoundTrip) {
        service?.ping()
    }
}

// Status propagation
private fun updateStatus(newStatus: NetworkStatus) {
    val start = SystemClock.elapsedRealtimeNanos()
    _networkStatus.value = newStatus
    val duration = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
    PerformanceMetrics.statusPropagation.record(duration)
}
```

#### Step 3: Create Metrics Dashboard

```kotlin
// Debug menu or settings screen
fun displayMetricsDashboard() {
    val stats = PerformanceMetrics.getAllStats()

    val report = buildString {
        appendLine("=== Performance Metrics ===\n")
        stats.forEach { stat ->
            appendLine("${stat.name}:")
            appendLine("  Count: ${stat.count}")
            appendLine("  Average: ${"%.2f".format(stat.average)}ms")
            appendLine("  Min/Max: ${stat.min}ms / ${stat.max}ms")
            appendLine("  Within target: ${stat.withinTarget}/${stat.count}")
            if (stat.exceedingTarget > 0) {
                appendLine("  ⚠️ Exceeding target: ${stat.exceedingTarget}")
            }
            appendLine()
        }
    }

    Log.i("Metrics", report)
    // Also display in UI if needed
}
```

#### Step 4: Add CI Performance Tests

```kotlin
@Test
fun testPerformanceTargets() = runTest {
    // Initialize
    protocol.initialize(testConfig)

    // Let metrics collect
    delay(1000)

    // Verify all metrics within targets
    PerformanceMetrics.assertAllWithinTarget()
}
```

### Success Criteria

- ✅ All critical paths instrumented
- ✅ Dashboard shows real-time metrics
- ✅ Alerts when metrics exceed targets
- ✅ CI tests fail on performance regression
- ✅ 100% of operations measured

---

## Task 5.2: Threading Test Suite

### Goal

Create comprehensive tests covering all threading scenarios.

### Implementation

#### Step 1: Concurrent Access Tests

**File**: `app/src/test/java/com/lxmf/messenger/threading/ConcurrentAccessTest.kt`

```kotlin
@Test
fun testConcurrentStatusReads() = runTest {
    // Launch many concurrent status reads
    val results = (1..100).map {
        async {
            protocol.networkStatus.value
        }
    }.awaitAll()

    // All should succeed
    assertEquals(100, results.size)
}

@Test
fun testConcurrentIpcCalls() = runTest {
    val results = (1..50).map { i ->
        async {
            protocol.sendMessage("test_$i")
        }
    }.awaitAll()

    // All should complete
    assertEquals(50, results.size)
}
```

#### Step 2: Stress Tests

```kotlin
@Test
fun testStressInitialization() = runTest {
    // Rapid init/shutdown cycles
    repeat(100) {
        protocol.initialize(testConfig)
        protocol.shutdown()
    }

    // Should complete without crashes
}

@Test
fun testStressPythonCalls() = runTest {
    // 10,000 Python operations
    repeat(10_000) { i ->
        PythonExecutor.execute("stress_$i") {
            wrapper.callAttr("simple_method", i)
        }
    }

    // Verify no leaks, no crashes
    val metrics = PythonExecutor.getMetrics()
    assertEquals(10_000, metrics.completed)
    assertEquals(0, metrics.failed)
}
```

#### Step 3: Race Condition Detection

```kotlin
@Test
fun testNoInitializationRace() = runTest {
    // Start initialization from multiple places
    val job1 = async { protocol.initialize(testConfig) }
    val job2 = async { protocol.initialize(testConfig) }

    val results = awaitAll(job1, job2)

    // Should handle gracefully (only one init)
    // Check logs: should only see one "Initializing" message
}
```

#### Step 4: Thread Leak Detection

```kotlin
@Test
fun testNoThreadLeaks() = runTest {
    val initialThreadCount = Thread.activeCount()

    // Do lots of work
    repeat(1000) {
        protocol.sendMessage("test_$it")
    }

    // Let coroutines complete
    delay(5000)

    val finalThreadCount = Thread.activeCount()

    // Thread count should be stable (within ~5 threads)
    assertTrue(
        "Thread leak detected: $initialThreadCount -> $finalThreadCount",
        abs(finalThreadCount - initialThreadCount) < 5
    )
}
```

### Success Criteria

- ✅ 90%+ code coverage for threading code
- ✅ Stress tests pass (10,000 operations)
- ✅ Race condition tests pass
- ✅ No thread leaks after 1 hour runtime
- ✅ All timeout boundaries tested

---

## Task 5.3: Documentation

### Goal

Document threading architecture, contracts, and best practices.

### Implementation

#### Step 1: Inline Documentation

Add to every class with threading concerns:

```kotlin
/**
 * Thread Safety: Calls can be made from any thread.
 * Uses single-threaded PythonExecutor internally for serialization.
 *
 * Dispatcher: All public methods use PythonExecutor.
 * Callbacks: Delivered on caller's dispatcher.
 *
 * Lifecycle: Tied to service lifecycle. Cancel scope in onDestroy().
 */
class ReticulumWrapper {
    // ...
}
```

#### Step 2: Architecture Diagram

Create diagram showing threading model:

```
┌─────────────────────────────────────────────┐
│                   App Process                │
│  Main Thread → ViewModels → StateFlow        │
└───────────────────┬─────────────────────────┘
                    │ IPC (async callbacks)
┌───────────────────┴─────────────────────────┐
│              Service Process                 │
│  ┌─────────────────────────────────────────┐│
│  │ Main Thread: Lifecycle only             ││
│  └─────────────────────────────────────────┘│
│  ┌─────────────────────────────────────────┐│
│  │ PythonExecutor: Single thread           ││
│  │ - All Python calls                      ││
│  │ - Sequential execution                  ││
│  └─────────────────────────────────────────┘│
│  ┌─────────────────────────────────────────┐│
│  │ Dispatchers.IO: Database, files         ││
│  └─────────────────────────────────────────┘│
│  ┌─────────────────────────────────────────┐│
│  │ Dispatchers.Default: CPU work           ││
│  └─────────────────────────────────────────┘│
└──────────────────────────────────────────────┘
```

#### Step 3: Troubleshooting Guide

Already created in `docs/TROUBLESHOOTING.md`. Ensure it's kept up-to-date with any new patterns or issues discovered.

#### Step 4: Best Practices Document

Already created in `docs/KOTLIN_COROUTINE_PATTERNS.md`. Add any project-specific patterns discovered during implementation.

### Success Criteria

- ✅ Every class documents thread safety
- ✅ Architecture diagram created
- ✅ Troubleshooting guide complete (20+ scenarios)
- ✅ Best practices documented (10+ patterns)
- ✅ 100% of thread-sensitive code commented

---

## Phase 5 Completion Checklist

- [ ] Task 5.1: Performance metrics
  - [ ] Metrics framework implemented
  - [ ] All critical paths instrumented
  - [ ] Dashboard created
  - [ ] CI tests added
  - [ ] All metrics within targets
- [ ] Task 5.2: Threading tests
  - [ ] Concurrent access tests created
  - [ ] Stress tests pass (10,000 ops)
  - [ ] Race detection tests pass
  - [ ] Thread leak tests pass
  - [ ] 90%+ coverage achieved
- [ ] Task 5.3: Documentation
  - [ ] Inline documentation added
  - [ ] Architecture diagram created
  - [ ] Troubleshooting guide updated
  - [ ] Best practices documented
- [ ] All tests passing in CI
- [ ] Performance dashboard live
- [ ] Code reviewed and approved

## Long-Term Monitoring

After initial implementation, continue monitoring:

### Daily
- Check metrics dashboard for any exceeding targets
- Review logs for threading warnings

### Weekly
- Run full stress test suite
- Check for thread leaks (24-hour run)
- Review performance trends

### Monthly
- Update documentation based on learnings
- Add new tests for discovered scenarios
- Review and adjust performance targets

### Per Release
- Run full threading test suite
- Verify all metrics within targets
- Performance regression testing
- Update architecture docs if changed

---

## Final Success Metrics

The threading redesign is complete when:

### Performance
- ✅ Service startup: < 3 seconds
- ✅ IPC round-trip: < 10ms
- ✅ Status propagation: < 10ms
- ✅ Message delivery: < 500ms (active)
- ✅ CPU usage (idle): < 1%
- ✅ Battery impact: < 2% over 24 hours

### Reliability
- ✅ Crash rate: < 0.1% DAU
- ✅ ANR rate: 0%
- ✅ Thread leaks: 0 over 24 hours
- ✅ Race conditions: 0 detected

### Quality
- ✅ Test coverage: > 90%
- ✅ Code coverage: Threading code fully tested
- ✅ Documentation: Complete and accurate
- ✅ Team confidence: High

---

## Celebration!

🎉 When all 5 phases complete, the threading architecture is production-ready!

**Achievements**:
- Event-driven, not polling
- No blocking on binder threads
- Proper dispatcher usage throughout
- Comprehensive test coverage
- Full observability

**Benefits Realized**:
- 50% CPU reduction when idle
- Better battery life
- Instant status updates
- No ANRs
- Maintainable codebase

---

*Test everything. Measure everything. Document everything.*
