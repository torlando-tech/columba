# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-23)

**Core value:** Reliable off-grid messaging with a polished, responsive user experience.
**Current focus:** v0.10.0 Voice Messages -- Phase 8: Recording and Send

## Current Position

Phase: 8 of 10 (Recording and Send Path) -- IN PROGRESS
Plan: 1 of 2 in phase (08-01 complete, 08-02 pending)
Status: Plan 08-01 complete, ready for 08-02
Last activity: 2026-02-24 -- Completed 08-01-PLAN.md (VoiceMessageRecorder + AudioPermissionManager)

Progress: [██░░░░░░░░░░] ~17% -- Phase 8 plan 1/2 complete (4/8 voice message plans done)

## Milestone Summary

**v0.10.0 Voice Messages**

| Phase | Goal | Requirements | Status |
|-------|------|--------------|--------|
| 7. Protocol Foundation | Audio round-trips through LXMF pipeline | PROTO-01..05 | **COMPLETE** |
| 8. Recording + Send | Record and send voice messages | REC-01,05,07 EDGE-01,07 | **In progress** (1/2 plans) |
| 9. Playback + Bubble | Inline playback with waveform | PLAY-01..07 EDGE-02,06 | Not started |
| 10. UI Polish + Edge Cases | Gesture polish, interruption handling | REC-02..04,06 EDGE-03..05 | Not started |

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

### Phase 8 Delivered (so far)

- **08-01:** VoiceMessageRecorder (AudioRecord 24kHz mono + Opus VOICE_MEDIUM encoding + length-prefixed frame accumulation + 300ms discard + 30s auto-stop + audio focus + waveform peaks) and AudioPermissionManager (RECORD_AUDIO check)

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
| Audio vars null in sendMessage() until Phase 8 | Placeholders declared at correct call site; Phase 8 wires VoiceMessageViewModel output in | 7-03 |
| retryAudioCodecId falls back to "opus_vm" | Prevents retry failure on metadata parse edge case while preserving codec intent | 7-03 |
| Audio focus released before 300ms discard check | Ensures tap-discards release focus; prevents permanently muting other apps | 8-01 |
| Partial final frames discarded (not zero-padded) | Avoids audible silence artifacts at recording end | 8-01 |
| Both API 26+ and deprecated audio focus paths | minSdk is 24; need deprecated requestAudioFocus for pre-O devices | 8-01 |
| VoiceRecording.equals uses contentEquals for ByteArray | Standard Kotlin data class pattern for array fields | 8-01 |

### Pending Todos

3 todos in `.planning/todos/pending/`:
- **Investigate native memory growth using Python profiling** (HIGH priority)
- **Make discovered interfaces page event-driven** (ui)
- **Refactor PropagationNodeManager to extract components** (architecture)

### Blockers/Concerns

None. Plan 08-02 can proceed immediately.

## Session Continuity

Last session: 2026-02-24T15:50:18Z
Stopped at: Completed 08-01-PLAN.md (VoiceMessageRecorder + AudioPermissionManager)
Resume file: None
Next: Plan 08-02 -- VoiceMessageViewModel, permission launcher, mic button wiring in MessagingScreen
