# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-23)

**Core value:** Reliable off-grid messaging with a polished, responsive user experience.
**Current focus:** v0.10.0 Voice Messages -- Phase 9: Playback Polish

## Current Position

Phase: 9 of 10 (Playback Polish) -- In Progress
Plan: 2 of 3 in phase (09-01, 09-02 complete)
Status: In progress
Last activity: 2026-03-12 -- Completed 09-02: Oboe player rewrite, duration extraction, audio focus

Progress: [████████░░░░] ~60% -- Phase 9 plan 2/3 complete (7/8 voice message plans done)

## Milestone Summary

**v0.10.0 Voice Messages**

| Phase | Goal | Requirements | Status |
|-------|------|--------------|--------|
| 7. Protocol Foundation | Audio round-trips through LXMF pipeline | PROTO-01..05 | **COMPLETE** |
| 8. Recording + Send + Playback | Record, preview, send, and play voice messages | REC-01,04..07,09 PLAY-01..04 EDGE-01,06,07 | **COMPLETE** |
| 9. Playback Polish | Unplayed indicator, single-playback, Oboe output | PLAY-05..07 EDGE-02 | In progress (1/3 plans) |
| 10. UI Polish + Edge Cases | Slide-to-cancel, mic/send swap, interruptions | REC-02,03 EDGE-03..05 | Not started |

## Accumulated Context

### From Previous Milestones

- LXST-kt audio library already in codebase (Opus, Codec2, Oboe capture/playback)
- FIELD_AUDIO = 0x07 already defined in Python wrapper
- MessageEntity.fieldsJson already supports audio field storage
- Image attachment pipeline (FIELD_IMAGE) is the closest pattern to follow

### Phase 7 Delivered

- **07-01:** LXMF transport pipeline -- audioData/audioCodecId through full IPC stack (Kotlin -> AIDL -> ServiceBinder -> Python -> FIELD_AUDIO)
- **07-02:** Kotlin extraction -- MessageUi audio fields, MessageMapper.extractAudioBytes/extractAudioMetadata/hasAudioField, AudioMetadata data class
- **07-03:** ViewModel send flow -- buildFieldsJson Field 7 packing, sendMessage audio pass-through, handleSendSuccess local storage, retryFailedMessage re-extraction

### Phase 8 Delivered

- **08-01:** VoiceMessageRecorder (AudioRecord 24kHz mono + Opus VOICE_MEDIUM encoding + length-prefixed frame accumulation + 300ms discard + 30s auto-stop + audio focus + waveform peaks) and AudioPermissionManager (RECORD_AUDIO check)
- **08-02:** VoiceMessageViewModel + VoiceMessagePlayer + full UI integration:
  - Hold-to-record mic button (Box + pointerInput + detectTapGestures, NOT IconButton)
  - Recording indicator (pulsing red dot + timer replacing text field)
  - Preview bar with playback, waveform visualization, trash/send controls
  - VoiceMessagePlayer (Opus decode + AudioTrack MODE_STATIC + progress polling)
  - VoiceMessageBubble (inline audio bubble with play/pause, waveform, duration)
  - Voice-only text suppression (hides " " content for voice-only messages)
  - Shared WaveformBar composable with progress coloring
  - sendMessage wiring with audio bytes, codec ID, waveform peaks

### Decisions

| Decision | Rationale | Phase |
|----------|-----------|-------|
| Opus VOICE_MEDIUM (8kbps) | Balances quality and mesh-friendly size (~30KB/30s) | 7 |
| AudioRecord + Opus.encode() over NativeCaptureEngine | Avoids singleton conflict with voice calls; quality diff negligible for store-and-forward | 8 |
| Length-prefixed Opus frames (no Ogg container) | Compact, simple, sufficient for Columba-to-Columba; codec_id identifies format | 7 |
| 30s max duration | Keeps messages under ~30KB, practical for mesh links | 8 |
| Audio detected by JSONArray format in field 7 | Distinguishes audio ["codec_id","hex"] from legacy location string data in same field | 7-02 |
| audioDurationMs=null until Phase 9 playback | Duration in audio header requires decoding; deferred to avoid blocking toMessageUi() | 7-02 |
| Waveform not transmitted over wire for received messages | Avoids bloating LXMF wire format; computed lazily on first view in Phase 9 | 7-02 |
| audioDataPath added to AIDL alongside audioData | Large audio files bypass Android Binder 1MB IPC limit via temp file path, same as imageDataPath pattern | 7-01 |
| Field 7 disambiguation via isinstance on first element | list[0] is str = audio, bytes/str = legacy location JSON; no ambiguity possible | 7-01 |
| Audio focus released before 300ms discard check | Ensures tap-discards release focus; prevents permanently muting other apps | 8-01 |
| Partial final frames discarded (not zero-padded) | Avoids audible silence artifacts at recording end | 8-01 |
| Both API 26+ and deprecated audio focus paths | minSdk is 24; need deprecated requestAudioFocus for pre-O devices | 8-01 |
| VoiceRecording.equals uses contentEquals for ByteArray | Standard Kotlin data class pattern for array fields | 8-01 |
| Box + pointerInput over IconButton for mic button | IconButton's internal clickable modifier consumes touch events before child pointerInput can detect them | 8-02 |
| Mic button visibility independent of isRecording | Composable hosting tryAwaitRelease() MUST remain in composition tree for entire gesture; conditional removal kills the coroutine | 8-02 |
| Preview-before-send (not auto-send on release) | User requested confirmation flow; REC-09 promoted from v2 to v1 | 8-02 |
| VoiceMessagePlayer as standalone class (not ViewModel) | Used in both VoicePreviewBar (raw bytes) and VoiceMessageBubble (fieldsJson bytes); remember{} in each composable | 8-02 |
| AudioTrack MODE_STATIC for playback | Voice messages are short (<30s); decode all frames up front, write once, track progress via playbackHeadPosition | 8-02 |
| Voice-only messages use " " (space) as content | Sideband requires non-empty field 0; suppressed in UI via isBlank() check | 8-02 |

### Pending Todos

3 todos in `.planning/todos/pending/`:
- **Investigate native memory growth using Python profiling** (HIGH priority)
- **Make discovered interfaces page event-driven** (ui)
- **Refactor PropagationNodeManager to extract components** (architecture)

### Phase 9 Delivered

- **09-01:** voicePlayed persistence layer -- Room schema v43, MessageEntity column, domain Message field, MessageDao.markVoicePlayed(), ConversationRepository.markVoicePlayed()
- **09-02:** VoiceMessagePlayer rewrite -- NativePlaybackEngine (Oboe) replaces AudioTrack; messageId tracking; savedPositions for resume; AUDIOFOCUS_GAIN_TRANSIENT; countOpusFrames() duration extraction in MessageMapper; VoiceMessageBubble now shows correct duration without playback

### Decisions (Phase 9)

| Decision | Rationale | Phase |
|----------|-----------|-------|
| @ColumnInfo(defaultValue = "0") on voicePlayed entity field | Must match INTEGER NOT NULL DEFAULT 0 in migration SQL for Room schema validation | 9-01 |
| No DEFAULT NULL in voicePlayed migration | Follows project Room gotcha: DEFAULT NULL creates schema mismatch with Kotlin nullable field defaultValue annotation | 9-01 |
| voicePlayed defaults false for all messages | Non-voice messages are never "played" so indicator never shows; voice messages start unplayed until explicitly marked | 9-01 |
| List<FloatArray> (per-frame) instead of flat FloatArray for decoded frames | Required for position-indexed playback starting from saved frame index | 9-02 |
| getCallbackFrameCount() for progress (not getBufferedFrameCount) | Callback count reflects frames actually rendered to speaker; buffered count is ring buffer backlog | 9-02 |
| AudioTrack MODE_STATIC replaced by NativePlaybackEngine (Oboe) | Low-latency SPSC ring buffer; consistent with telephony infrastructure; required by PLAY-07 | 9-02 |
| countOpusFrames() walks hex wire format directly | No ByteArray allocation needed for a counting operation; fast enough for toMessageUi() call path | 9-02 |

### Blockers/Concerns

None.

## Session Continuity

Last session: 2026-03-12
Stopped at: Completed 09-02-PLAN.md
Resume file: None
Next: Phase 9 plan 03 -- unplayed indicator UI (dot badge, mark-played on first full listen)
