# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-23)

**Core value:** Reliable off-grid messaging with a polished, responsive user experience.
**Current focus:** v0.10.0 Voice Messages -- Phase 7: Protocol and Transport Foundation

## Current Position

Phase: 7 of 10 (Protocol and Transport Foundation)
Plan: 2 of 3 in phase (07-02 complete)
Status: In progress
Last activity: 2026-02-24 -- Completed 07-02-PLAN.md (audio fields in MessageUi/MessageMapper)

Progress: [░░░░░░░░░░░░] 0% -- 0/4 phases complete (Phase 7 in progress)

## Milestone Summary

**v0.10.0 Voice Messages**

| Phase | Goal | Requirements | Status |
|-------|------|--------------|--------|
| 7. Protocol Foundation | Audio round-trips through LXMF pipeline | PROTO-01..05 | Not started |
| 8. Recording + Send | Record and send voice messages | REC-01,05,07 EDGE-01,07 | Not started |
| 9. Playback + Bubble | Inline playback with waveform | PLAY-01..07 EDGE-02,06 | Not started |
| 10. UI Polish + Edge Cases | Gesture polish, interruption handling | REC-02..04,06 EDGE-03..05 | Not started |

## Accumulated Context

### From Previous Milestones

- LXST-kt audio library already in codebase (Opus, Codec2, Oboe capture/playback)
- FIELD_AUDIO = 0x07 already defined in Python wrapper
- MessageEntity.fieldsJson already supports audio field storage
- Image attachment pipeline (FIELD_IMAGE) is the closest pattern to follow

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

### Pending Todos

3 todos in `.planning/todos/pending/`:
- **Investigate native memory growth using Python profiling** (HIGH priority)
- **Make discovered interfaces page event-driven** (ui)
- **Refactor PropagationNodeManager to extract components** (architecture)

### Blockers/Concerns

None yet.

## Session Continuity

Last session: 2026-02-24T05:06:10Z
Stopped at: Completed 07-02-PLAN.md (audio fields in MessageUi and MessageMapper)
Resume file: None
Next: 07-03 (audio send pipeline - encode voice messages into fieldsJson for LXMF transport)
