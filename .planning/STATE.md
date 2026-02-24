# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-23)

**Core value:** Reliable off-grid messaging with a polished, responsive user experience.
**Current focus:** v0.10.0 Voice Messages — Defining requirements

## Current Position

Phase: Not started (defining requirements)
Plan: —
Status: Defining requirements
Last activity: 2026-02-23 — Milestone v0.10.0 started

Progress: [░░░░░░░░░░░░] 0% — Requirements phase

## Milestone Summary

**v0.10.0 Voice Messages — Initializing**

| Phase | Goal | Requirements | Status |
|-------|------|--------------|--------|
| TBD | — | — | Not started |

## Accumulated Context

### From Previous Milestones

- LXST-kt audio library already in codebase (Opus, Codec2, Oboe capture/playback)
- FIELD_AUDIO = 0x07 already defined in Python wrapper
- MessageEntity.fieldsJson already supports audio field storage
- Image attachment pipeline (FIELD_IMAGE) is the closest pattern to follow

### Decisions

| Decision | Rationale | Phase |
|----------|-----------|-------|
| Opus VOICE_MEDIUM (8kbps) | Balances quality and mesh-friendly size (~30KB/30s) | — |
| Hold-to-record UX | Familiar pattern (WhatsApp/Signal), quick for short messages | — |
| 30s max duration | Keeps messages under ~30KB, practical for mesh links | — |
| LXST-kt for record + playback | Already in codebase, native Oboe performance, Opus codec ready | — |
| Play button + waveform UI | Rich inline experience, matches modern messenger expectations | — |

### Patterns Established

- **WhileSubscribed(5000L)**: Standard timeout for Room-backed StateFlows
- **Turbine test pattern**: Keep collector active inside test block when testing StateFlow.value with WhileSubscribed
- **BuildConfig feature flags**: Clean pattern for debug-only functionality with zero release overhead
- **Image attachment pipeline**: FIELD_IMAGE → fieldsJson → MessageUi → inline rendering (pattern to follow for audio)

### Pending Todos

3 todos in `.planning/todos/pending/`:
- **Investigate native memory growth using Python profiling** (HIGH priority)
- **Make discovered interfaces page event-driven** (ui)
- **Refactor PropagationNodeManager to extract components** (architecture)

## Session Continuity

Last session: 2026-02-23
Stopped at: Milestone v0.10.0 initialization
Resume file: None
Next: Complete requirements → roadmap → `/gsd:plan-phase [N]`
