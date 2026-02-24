# Columba

## What This Is

Columba is an Android LXMF messenger built on the Reticulum mesh networking stack. It bridges Python (Reticulum/LXMF) with Kotlin via Chaquopy, supporting BLE, USB, and TCP interfaces for off-grid and resilient communication.

## Core Value

Reliable off-grid messaging with a polished, responsive user experience.

## Requirements

### Validated

- ✓ Multi-process architecture (UI + service) — existing
- ✓ LXMF messaging over Reticulum — existing
- ✓ BLE, USB (RNode), TCP interface support — existing
- ✓ Interface Discovery feature — v0.7.2
- ✓ Auto-relay selection — existing
- ✓ **PERF-01**: App maintains responsive UI regardless of background operations — v0.7.3
- ✓ **PERF-02**: No progressive performance degradation over app runtime — v0.7.3
- ✓ **PERF-03**: Interface Discovery screen scrolls smoothly — v0.7.3
- ✓ **RELAY-01**: Relay auto-selection does not loop (add/remove/add cycle) — v0.7.3
- ✓ **RELAY-02**: Root cause of automatic relay unset identified and fixed — v0.7.3
- ✓ **ANNOUNCE-01**: Clear All Announces preserves contacts in My Contacts — v0.7.3
- ✓ **OFFLINE-MAP-01**: Offline maps render correctly after extended offline periods — v0.7.3
- ✓ **UX-LOADING-01**: Show loading indicators instead of flashing empty states — v0.7.3

### Active

- [ ] **VM-01**: User can record a voice message by holding a mic button in the chat input
- [ ] **VM-02**: Voice message is encoded with Opus VOICE_MEDIUM (8kbps) via LXST-kt
- [ ] **VM-03**: Voice message is sent as FIELD_AUDIO (0x07) in LXMF message
- [ ] **VM-04**: Voice messages render inline as a bubble with play/pause button and waveform
- [ ] **VM-05**: Voice message playback uses LXST-kt (Opus decode + Oboe playback)
- [ ] **VM-06**: Recording has a 30-second maximum duration (~30KB at VOICE_MEDIUM)

### Out of Scope

- iOS version — Android-first approach
- Desktop version — mobile focus
- Voice calls over mesh — separate feature (LXST telephone already exists)
- Audio effects/filters on voice messages — unnecessary complexity

## Current Milestone: v0.10.0 Voice Messages

**Goal:** Add voice message recording, sending, and inline playback to conversations.

**Target features:**
- Hold-to-record mic button in chat input area
- Opus VOICE_MEDIUM encoding via LXST-kt (~30KB for 30s)
- LXMF FIELD_AUDIO transport
- Inline audio bubble with play/pause + waveform visualization
- Playback via LXST-kt Opus decode + Oboe native playback

## Context

**Current State:**
- ~205K lines of Kotlin
- Tech stack: Kotlin, Compose, Hilt, Room, Chaquopy (Python bridge)
- LXST-kt audio library already in repo (Opus, Codec2, Oboe capture/playback)
- FIELD_AUDIO = 0x07 already defined in Python wrapper and recognized as meaningful field
- MessageEntity.fieldsJson already supports audio field storage
- Sentry performance monitoring integrated

**Known Issues:**
- Native memory growth (~1.4 MB/min) in Python/Reticulum layer
- PropagationNodeManager is large class — could extract RelaySelectionStateMachine

## Constraints

- **Platform**: Android 6.0+ (API 24), ARM64 only
- **Architecture**: Must not break multi-process service model
- **Testing**: Changes should be testable without requiring physical hardware where possible

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Focus on #340 and #343 first | Highest user impact, both high severity | ✓ Fixed in v0.7.3 |
| Defer #338 and #342 | Lower severity, can address in next milestone | — Active for v0.7.4 |
| State machine for relay selection | Explicit states prevent re-entrancy bugs | ✓ Loop eliminated |
| 1s debounce + 30s cooldown | Prevents rapid Room invalidation triggers | ✓ No cascading |
| Exponential backoff on loop detection | Graceful degradation if edge cases occur | ✓ Good |
| @Stable annotations for Compose | Reduces unnecessary recompositions | ✓ Smooth scrolling |
| SQL subquery for contact-aware delete | More efficient than app-side filtering | ✓ Good |
| Cache MapLibre style JSON locally | Enables indefinite offline map rendering | ✓ Good |
| Boolean isLoading flag pattern | Consistent with existing MapViewModel | ✓ Good |
| Opus VOICE_MEDIUM for voice messages | 8kbps balances quality and mesh-friendly size (~30KB/30s) | — Pending |
| Hold-to-record UX | Familiar pattern (WhatsApp/Signal), quick for short messages | — Pending |
| 30s max voice message duration | Keeps messages under ~30KB, practical for mesh links | — Pending |
| LXST-kt for record and playback | Already in codebase, native Oboe performance, Opus codec ready | — Pending |

---
*Last updated: 2026-02-23 after v0.10.0 milestone start*
