---
phase: 09-mixer-pipeline
plan: 02
subsystem: audio
tags: [tone-generation, sine-wave, fade, easing, lxst, kotlin]

# Dependency graph
requires:
  - phase: 08
    provides: Source/LocalSource base classes, Sink interface
provides:
  - ToneSource class with sine wave generation
  - Smooth fade in/out for click-free tone playback
  - Phase accumulator for continuous tone generation
affects: [09-03, 10-telephony]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - Phase accumulator (Double theta) for continuous sine wave
    - Linear easing for click-free audio transitions
    - Coroutine-based audio generation loop

key-files:
  created:
    - reticulum/src/main/java/com/lxmf/messenger/reticulum/audio/lxst/ToneSource.kt
  modified: []

key-decisions:
  - "382Hz default frequency matches Python LXST Telephony.py (not 440Hz ITU-T)"
  - "Float32 output directly to sink (local playback path, no encoding)"
  - "20ms ease time for fade in/out (matches Python LXST)"
  - "Double for theta to avoid floating point drift over long tones"

patterns-established:
  - "LocalSource subclass pattern: override channels/sampleRate, implement start/stop/isRunning"
  - "Easing pattern: easeGain ramps 0->1 on start, 1->0 on stop with easingOut flag"

# Metrics
duration: 5min
completed: 2026-02-04
---

# Phase 9 Plan 02: ToneSource Summary

**ToneSource generates 382Hz dial tones with 20ms linear fade in/out using persistent phase accumulator for click-free audio**

## Performance

- **Duration:** 5 min
- **Started:** 2026-02-04T22:51:11Z
- **Completed:** 2026-02-04T22:56:00Z
- **Tasks:** 2
- **Files created:** 1

## Accomplishments

- ToneSource class extends LocalSource for dial tone generation
- 382Hz default frequency matching Python LXST Telephony.py
- Phase accumulator (theta as Double) persists across frames for smooth continuous tone
- Linear fade in (20ms) on start prevents click
- Linear fade out on stop() prevents click
- Smooth gain transitions via currentGain -> targetGain convergence
- Float32 output directly to sink (local playback path)

## Task Commits

Each task was committed atomically:

1. **Task 1 & 2: Create ToneSource with sine wave and float32 output** - `272f828a` (feat)

**Plan metadata:** pending

## Files Created/Modified

- `reticulum/src/main/java/com/lxmf/messenger/reticulum/audio/lxst/ToneSource.kt` - Sine wave generator with easing (226 lines)

## Decisions Made

1. **382Hz default frequency** - Matches Python LXST Telephony.py:118, not standard 440Hz ITU-T. Frequency is configurable if user wants different tone.

2. **Float32 output directly to sink** - ToneSource pushes decoded float32 to sink for local playback. Encoding happens in transmit path (Mixer -> network), not in ToneSource.

3. **Double for theta** - Phase accumulator uses Double precision to avoid floating point drift over long dial tones.

4. **20ms ease time** - Matches Python LXST EASE_TIME_MS constant for consistent fade behavior.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Fixed channels property declaration**
- **Found during:** Task 1 (ToneSource creation)
- **Issue:** Plan showed `override val channels` but Source base class declares `var channels`
- **Fix:** Changed to `override var channels: Int = channels` matching LineSource pattern
- **Files modified:** ToneSource.kt
- **Verification:** Build compiles successfully
- **Committed in:** 272f828a

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** Kotlin/JVM requirement - abstract var cannot be overridden with val. No scope creep.

## Issues Encountered

None beyond the channels property fix documented above.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- ToneSource ready for integration with Mixer in Plan 03
- LocalSource subclass pattern established
- Easing/fade pattern ready for reuse in other audio components

---
*Phase: 09-mixer-pipeline*
*Completed: 2026-02-04*
