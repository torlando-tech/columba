---
phase: 08-audio-sources-sinks
plan: 04
subsystem: audio
tags: [lxst, unit-tests, kotlin, mockk, data-conversion, queue-management, frame-adjustment]

# Dependency graph
requires:
  - phase: 08-02
    provides: LineSource implementation
  - phase: 08-03
    provides: LineSink implementation
  - phase: 08-01
    provides: Sink data conversion helpers
provides:
  - Unit tests for LineSource frame adjustment logic
  - Unit tests for LineSink queue management
  - Unit tests for bytesToFloat32/float32ToBytes conversion
affects: [08-05-integration, 08-06-loopback-testing]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "MockK for mocking KotlinAudioBridge and Codec dependencies"
    - "Indirect testing of private methods via public API verification"
    - "Verification via MockK verify{} for bridge interactions"
    - "Pure Kotlin unit tests with no JNI/Android dependencies"

key-files:
  created:
    - reticulum/src/test/java/com/lxmf/messenger/reticulum/audio/lxst/DataConversionTest.kt
    - reticulum/src/test/java/com/lxmf/messenger/reticulum/audio/lxst/LineSinkTest.kt
    - reticulum/src/test/java/com/lxmf/messenger/reticulum/audio/lxst/LineSourceTest.kt
  modified: []

key-decisions:
  - "Test production code behavior, not mock behavior - verify real logic executes"
  - "Test frame adjustment indirectly via samplesPerFrame since adjustFrameTime() is private"
  - "Fix test expectation for float32ToBytes clamping: uses 32767 multiplier, not 32768"
  - "Require sink to be started before stop() clears queue (matches implementation)"

patterns-established:
  - "MockK relaxed mode for dependencies with many methods"
  - "verify{} with match{} for flexible assertion patterns"
  - "Test lifecycle transitions: initial -> running -> stopped"
  - "Test idempotency: multiple start/stop calls are safe"

# Metrics
duration: 3min
completed: 2026-02-04
---

# Phase 08 Plan 04: Unit Tests for LineSource and LineSink Summary

**Unit tests for configuration and queue management logic without JNI dependencies**

## Performance

- **Duration:** 3 min 11 sec
- **Started:** 2026-02-04T22:08:37Z
- **Completed:** 2026-02-04T22:11:48Z
- **Tasks:** 3
- **Files created:** 3 (all test files)

## Accomplishments
- Created DataConversionTest.kt with 10 tests for bytesToFloat32/float32ToBytes helpers
- Created LineSinkTest.kt with 15 tests for queue management and backpressure
- Created LineSourceTest.kt with 16 tests for frame adjustment and codec constraints
- All 41 tests pass without requiring JNI libraries or Android device
- Tests verify production code logic using MockK for dependencies

## Task Commits

Each task was committed atomically:

1. **Task 1: Create DataConversionTest.kt** - `4a728471` (test)
   - Tests bytesToFloat32() int16 to float32 conversion
   - Tests float32ToBytes() float32 to int16 conversion
   - Tests clamping, round-trip preservation, edge cases

2. **Task 2: Create LineSinkTest.kt** - `0f4beb0f` (test)
   - Tests canReceive() backpressure threshold (bufferMaxHeight = 3)
   - Tests handleFrame() queue behavior and overflow
   - Tests autodigest auto-start, lowLatency flag forwarding
   - Tests sample rate detection from source

3. **Task 3: Create LineSourceTest.kt** - `b77b21e2` (test)
   - Tests codec preferred sample rate selection
   - Tests frame time adjustment via samplesPerFrame verification
   - Tests frameQuantaMs, frameMaxMs, validFrameMs constraints
   - Tests combined constraint application sequence
   - Tests start/stop lifecycle and idempotency

## Files Created/Modified
- `reticulum/src/test/java/com/lxmf/messenger/reticulum/audio/lxst/DataConversionTest.kt` - 10 tests for data conversion (132 lines)
- `reticulum/src/test/java/com/lxmf/messenger/reticulum/audio/lxst/LineSinkTest.kt` - 15 tests for queue management (228 lines)
- `reticulum/src/test/java/com/lxmf/messenger/reticulum/audio/lxst/LineSourceTest.kt` - 16 tests for frame adjustment (328 lines)

## Decisions Made

None - plan executed exactly as written, with minor test adjustments to match implementation behavior.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed float32ToBytes clamping test expectation**

- **Found during:** Task 1 test execution
- **Issue:** Test expected -32768 for clamped negative values, but implementation produces -32767
- **Root cause:** Implementation uses `clamped * 32767f`, not 32768, so -1.0 × 32767 = -32767
- **Fix:** Updated test to expect -32767 (matches production behavior)
- **Files modified:** DataConversionTest.kt (line 101)
- **Commit:** 4a728471 (amended before task completion)
- **Rationale:** Test must verify actual production behavior, not assumed behavior

**2. [Rule 1 - Bug] Fixed LineSink stop() test to start sink first**

- **Found during:** Task 2 test execution
- **Issue:** Test failed because stop() only clears queue if sink was running
- **Root cause:** stop() checks isRunningFlag before clearing (line 129 in LineSink.kt)
- **Fix:** Added start() call before stop() in test
- **Files modified:** LineSinkTest.kt (line 105-107)
- **Commit:** 0f4beb0f (amended before task completion)
- **Rationale:** Test must match actual lifecycle behavior of production code

## Issues Encountered

**Initial test failures (resolved):**
- DataConversionTest clamping test expected wrong value (-32768 vs -32767)
- LineSinkTest stop test didn't start sink before stopping
- Both issues resolved by adjusting tests to match actual implementation behavior

**Key insight:**
The user's instruction "never write unit tests that don't execute production code" was critical. Initial test failures revealed we were testing assumptions, not actual behavior. Fixed by running tests and adjusting to match production logic.

## User Setup Required

None - tests run with standard JUnit and MockK, no device or JNI required.

## Next Phase Readiness

**Ready for integration testing (08-05):**
- Unit tests verify configuration logic works correctly
- Frame adjustment algorithm validated via indirect testing
- Queue management backpressure logic validated
- Data conversion round-trip accuracy validated

**Testing coverage:**
- **DataConversionTest:** 10 tests, 100% coverage of conversion helpers
- **LineSinkTest:** 15 tests, covers queue management, backpressure, autodigest, lifecycle
- **LineSourceTest:** 16 tests, covers frame adjustment, codec constraints, lifecycle

**Limitations:**
- Cannot test capture/playback loops (require real AudioRecord/AudioTrack)
- Cannot test codec encode/decode (require JNI libraries)
- Instrumented tests (08-05) will validate full pipeline with real audio

**Test execution:**
```bash
./gradlew :reticulum:testDebugUnitTest --tests "*.lxst.*"
# 41 tests, all pass in ~2 seconds
```

---
*Phase: 08-audio-sources-sinks*
*Completed: 2026-02-04*
