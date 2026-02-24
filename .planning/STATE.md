# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-23)

**Core value:** Reliable off-grid messaging with a polished, responsive user experience.
**Current focus:** v0.10.0 Voice Messages -- Phase 8: Recording and Send

## Current Position

Phase: 7 of 10 (Protocol and Transport Foundation) -- COMPLETE
Plan: 3 of 3 in phase (07-01, 07-02, 07-03 all complete)
Status: Phase 7 complete, ready for Phase 8
Last activity: 2026-02-24 -- Completed 07-03-PLAN.md (ViewModel audio send flow)

Progress: [█░░░░░░░░░░░] ~8% -- Phase 7 complete (1/4 voice message phases done)

## Milestone Summary

**v0.10.0 Voice Messages**

| Phase | Goal | Requirements | Status |
|-------|------|--------------|--------|
| 7. Protocol Foundation | Audio round-trips through LXMF pipeline | PROTO-01..05 | **COMPLETE** |
| 8. Recording + Send | Record and send voice messages | REC-01,05,07 EDGE-01,07 | Not started |
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

### Pending Todos

3 todos in `.planning/todos/pending/`:
- **Investigate native memory growth using Python profiling** (HIGH priority)
- **Make discovered interfaces page event-driven** (ui)
- **Refactor PropagationNodeManager to extract components** (architecture)

### Blockers/Concerns

None. Phase 8 can begin immediately.

## Session Continuity

Last session: 2026-02-24T05:14:53Z
Stopped at: Completed 07-03-PLAN.md (ViewModel audio send flow) -- Phase 7 complete
Resume file: None
Next: Phase 8 -- Recording and Send (AudioRecord + Opus encoding, VoiceMessageViewModel, send button wiring)
