---
phase: 10
plan: 01
subsystem: messaging-ui
tags: [compose, animation, gesture, haptic, voice-recording]
requires: [08-02]
provides: [slide-to-cancel-gesture, animated-mic-send-swap]
affects: [10-02]
tech-stack:
  added: []
  patterns:
    - detectHorizontalDragGestures for slide-to-cancel
    - AnimatedVisibility with fade+scale for button transitions
    - animateFloatAsState with spring for offset snap-back
key-files:
  created: []
  modified:
    - app/src/main/java/com/lxmf/messenger/ui/screens/MessagingScreen.kt
decisions:
  - "AnimatedVisibility wraps send button only; mic button stays in plain if to preserve tryAwaitRelease() coroutine"
  - "Slide gesture goes on recording indicator Box, not mic button Box, to avoid conflicting with detectTapGestures"
  - "cancelThresholdPx computed as 1/3 screen width using LocalDensity + LocalConfiguration"
metrics:
  duration: "12m 17s"
  completed: "2026-03-12"
---

# Phase 10 Plan 01: Recording UI Polish Summary

**One-liner:** Slide-to-cancel gesture on recording bar (spring snap-back, haptic at 1/3 screen) + animated fade+scale send/mic button swap.

## What Was Built

### Task 1: Slide-to-cancel gesture on recording indicator bar

Added horizontal drag gesture detection to the `errorContainer`-colored Box that appears during recording. Key implementation details:

- `slideOffset` and `cancelTriggered` state tracked inside the `isRecording` branch
- `animateFloatAsState` with `spring(dampingRatio = Spring.DampingRatioMediumBouncy)` snaps the bar back when finger lifts without cancelling
- `cancelThresholdPx` = screen width / 3 (via `LocalConfiguration + LocalDensity`)
- `detectHorizontalDragGestures` on the recording Box: `onHorizontalDrag` clamps offset to `[-threshold*1.5, 0]`, triggers haptic (`HapticFeedbackType.LongPress`) when crossing threshold
- `onDragEnd`: calls `onMicCancel()` if `cancelTriggered`, resets state
- `onDragCancel`: resets state (no cancel)
- `onMicCancel` callback added to `MessageInputBar` signature, wired at call site to `voiceMessageViewModel.cancelRecording()`
- "< Slide to cancel" hint text fades in during drag (alpha 0.4 → 1.0); "Release to cancel" appears in `error` color past threshold
- The gesture is placed on the **recording indicator Box**, not the mic button Box, so it does not conflict with `detectTapGestures` on the mic button

### Task 2: Animated mic/send button swap

Replaced the plain `if (hasTextOrAttachments)` conditional for the send button with `AnimatedVisibility`:

```kotlin
AnimatedVisibility(
    visible = hasTextOrAttachments && pendingVoiceRecording == null,
    enter = fadeIn() + scaleIn(initialScale = 0.8f),
    exit = fadeOut() + scaleOut(targetScale = 0.8f),
) { /* send button */ }
```

The mic button remains in a plain `if (!hasTextOrAttachments && pendingVoiceRecording == null)` block. This is intentional — the `pointerInput(Unit) { detectTapGestures { tryAwaitRelease() } }` coroutine must not be torn down by animation state changes during an active hold-to-record gesture. Wrapping it in `AnimatedVisibility` would remove/recompose the composable and kill the coroutine.

## Decisions Made

| Decision | Rationale |
|----------|-----------|
| AnimatedVisibility wraps send button only; mic stays in plain if | Mic button hosts tryAwaitRelease() — must remain in composition tree during recording gesture |
| Slide gesture on recording indicator Box, not mic button | Mic button uses detectTapGestures; placing detectHorizontalDragGestures on a separate sibling element avoids gesture conflict |
| spring(DampingRatioMediumBouncy) for snap-back | Gives natural bouncy feel when finger releases without cancelling |
| cancelThresholdPx = screenWidthDp / 3 | Same ratio used in typical messaging apps for slide-to-cancel |

## Deviations from Plan

None - plan executed exactly as written. The import management required multiple edit passes due to tool behavior (subsequent Read calls required before each Edit), but the final result matches the plan specification exactly.

## Verification Results

1. `JAVA_HOME=/home/tyler/android-studio/jbr ./gradlew :app:compileNoSentryDebugKotlin` - BUILD SUCCESSFUL
2. `detectHorizontalDragGestures` present in MessagingScreen.kt (line 2499)
3. `AnimatedVisibility` present in MessagingScreen.kt (line 2702)
4. `onMicCancel` declared (line 2341), wired at call site (line 1356), called in onDragEnd (line 2503)
5. `cancelThresholdPx` present (line 2482)
6. `HapticFeedbackType.LongPress` in recording drag handler (line 2516)
7. Mic button `pointerInput(Unit) { detectTapGestures }` at line 2743 is NOT inside AnimatedVisibility (it's in plain `if` at line 2738)

## Next Phase Readiness

Phase 10 plan 02 (interruptions: phone calls, app backgrounding) can proceed. No blockers from this plan.
