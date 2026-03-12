---
phase: 09-playback-polish
plan: 02
subsystem: audio
tags: [oboe, nativeplaybackengine, opus, audio-focus, voice-messages, kotlin]

# Dependency graph
requires:
  - phase: 08-recording-send-playback
    provides: VoiceMessagePlayer (AudioTrack version), VoiceMessageRecorder constants, VoiceMessageBubble UI
  - phase: 09-playback-polish/01
    provides: Unplayed indicator in VoiceMessageBubble

provides:
  - Oboe-backed VoiceMessagePlayer via NativePlaybackEngine with messageId tracking
  - Position preservation (savedPositions map) for per-message resume
  - Audio focus management (AUDIOFOCUS_GAIN_TRANSIENT) for playback
  - Duration extraction from Opus frame count in MessageMapper (countOpusFrames)
  - Correct voice message duration display without requiring playback

affects:
  - 09-03 (single-playback enforcement / stop-others logic builds on currentMessageId)
  - 10-ui-polish (VoiceMessageBubble signature updated with messageId param)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Oboe static-playback pattern: decode all frames → write to ring buffer → startStream → poll getCallbackFrameCount → stopStream/destroy"
    - "Message ID tracking for single-playback enforcement via PlaybackUiState.currentMessageId"
    - "Position preservation via savedPositions: Map<String, Float> in PlaybackUiState"
    - "Opus frame counting from hex wire format for duration-without-decoding"

key-files:
  created: []
  modified:
    - app/src/main/java/com/lxmf/messenger/audio/VoiceMessagePlayer.kt
    - app/src/main/java/com/lxmf/messenger/ui/model/MessageMapper.kt
    - app/src/main/java/com/lxmf/messenger/ui/screens/MessagingScreen.kt

key-decisions:
  - "NativePlaybackEngine (Oboe) replaces AudioTrack MODE_STATIC; entire file rewritten"
  - "Frames decoded to List<FloatArray> (one per frame) instead of flat FloatArray to support position-indexed access"
  - "Progress uses getCallbackFrameCount() relative to totalFramesWritten (not getBufferedFrameCount)"
  - "Natural completion removes savedPositions entry; stop() saves it for resumption"
  - "VoicePreviewBar uses messageId='preview' (single recording, no conflict)"
  - "VoiceMessageBubble accepts messageId param with default empty string for backward compat"
  - "countOpusFrames() walks hex-encoded wire format directly (no ByteArray allocation)"

patterns-established:
  - "NativePlaybackEngine lifecycle for static (store-and-forward) audio: create→write→startStream→poll→stopStream→destroy"
  - "PlaybackUiState carries both transient (isPlaying, progressFraction) and persistent (savedPositions) state"

# Metrics
duration: 4min
completed: 2026-03-12
---

# Phase 9 Plan 02: Playback Polish - Oboe Player + Duration Summary

**Oboe-backed VoiceMessagePlayer with message ID tracking, position preservation, audio focus, and Opus frame count duration extraction**

## Performance

- **Duration:** 4 min
- **Started:** 2026-03-12T00:50:45Z
- **Completed:** 2026-03-12T00:54:34Z
- **Tasks:** 2
- **Files modified:** 3

## Accomplishments
- Full rewrite of VoiceMessagePlayer from AudioTrack to NativePlaybackEngine (Oboe): low-latency, lock-free SPSC ring buffer, zero AudioTrack references
- Single-playback enforcement foundation: play() takes messageId, currentMessageId tracked in PlaybackUiState; playing a different message saves current position and stops
- Position preservation: savedPositions map persists fractional progress per messageId; resuming plays from saved position
- Audio focus: AUDIOFOCUS_GAIN_TRANSIENT acquired before first sample plays, released on stop/completion; supports API 24+ (both deprecated and O+ paths)
- Duration from frame count: countOpusFrames() walks hex wire format without ByteArray allocation; each frame = 20ms; displayed in VoiceMessageBubble without any playback

## Task Commits

1. **Task 1: Rewrite VoiceMessagePlayer with Oboe, audio focus, message ID, and position preservation** - `fbcecb713` (feat)
2. **Task 2: Extract audio duration from Opus frame count in MessageMapper** - `625c5fb5d` (feat)

**Plan metadata:** (docs commit follows)

## Files Created/Modified
- `app/src/main/java/com/lxmf/messenger/audio/VoiceMessagePlayer.kt` - Full rewrite: NativePlaybackEngine, Context param, messageId tracking, position preservation, audio focus
- `app/src/main/java/com/lxmf/messenger/ui/model/MessageMapper.kt` - countOpusFrames() helper + durationMs computation in extractAudioMetadata()
- `app/src/main/java/com/lxmf/messenger/ui/screens/MessagingScreen.kt` - Updated VoicePreviewBar and VoiceMessageBubble call sites: Context injection, messageId param, play() signature

## Decisions Made
- Used `List<FloatArray>` (one per frame) instead of flat `FloatArray` for decoded frames — required for position-indexed playback starting from saved frame index
- Progress formula: `startFraction + (callbackFrames / totalFramesWritten) * (1 - startFraction)` — handles resume-from-middle correctly
- `VoiceMessageBubble` gets `messageId: String = ""` with default empty string — allows call sites without message ID to compile without changes
- Telephony guard: if `NativePlaybackEngine.isPlaying()` when `play()` is called, logs warning and sets error state rather than silently overriding
- `countOpusFrames` walks hex string directly (4 chars = 2-byte length prefix + frame length in hex chars) — avoids ByteArray allocation for what is just a counting operation

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] Updated MessagingScreen.kt call sites for new API**
- **Found during:** Task 1 (VoiceMessagePlayer rewrite)
- **Issue:** VoicePreviewBar and VoiceMessageBubble instantiated `VoiceMessagePlayer()` (no-arg); new constructor requires `Context`. Both `play()` calls lacked `messageId` param.
- **Fix:** Injected `LocalContext.current` via `context = androidx.compose.ui.platform.LocalContext.current` in both composables; added `messageId="preview"` in VoicePreviewBar; added `messageId: String = ""` param to VoiceMessageBubble and updated call site to pass `message.id`
- **Files modified:** app/.../ui/screens/MessagingScreen.kt
- **Committed in:** fbcecb713 (Task 1 commit)

---

**Total deviations:** 1 auto-fixed (1 missing critical / blocking — call site update required for compilation)
**Impact on plan:** Necessary for correctness. MessagingScreen.kt was the only consumer; update is minimal and in-scope.

## Issues Encountered
None beyond the call site update documented above.

## Next Phase Readiness
- Plan 09-03 (single-playback enforcement across all players) can now build on `currentMessageId` in `PlaybackUiState`
- VoiceMessageBubble displays correct duration for received messages without playback
- Position preservation infrastructure is in place; `clearSavedPositions()` available for navigation-away cleanup

---
*Phase: 09-playback-polish*
*Completed: 2026-03-12*
