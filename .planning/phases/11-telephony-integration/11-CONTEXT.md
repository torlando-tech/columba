# Phase 11: Telephony Integration - Context

**Gathered:** 2026-02-04
**Status:** Ready for planning

<domain>
## Phase Boundary

Port the Python LXST Telephone class and quality profiles to Kotlin, making the Kotlin audio pipeline (Phases 7-10) the primary call backend. Kotlin owns call state and profiles; Python handles Reticulum link setup and network transport.

</domain>

<decisions>
## Implementation Decisions

### Profile Architecture
- Profiles defined in **Kotlin only** — single source of truth
- All 8 profiles exposed: ULBW, LBW, NBW, MBW, HBW, WBW, UWB, ULL
- Kotlin pushes profile changes to Python (call_manager.on_profile_changed)
- Python is passive — receives profile info, doesn't query

### Claude's Discretion: Profile-to-Codec Mapping
- Claude decides whether Profile class contains codec config or Telephone maps it
- Should follow cleanest architecture for Kotlin

### Call State Machine
- **Kotlin drives state transitions** — Kotlin Telephone owns the state machine
- State machine mirrors Python LXST exactly (same states, same transitions, same names)
- Kotlin notifies Python via **direct method call** (call_manager.on_state_changed)
- **Kotlin initiates calls fully** — Telephone.call(destination) tells Python to set up link

### Audio Feedback Behavior
- Dial tone: **Kotlin ToneSource** (Phase 9) — pure Kotlin, low latency
- Ringtone: **User setting** — can choose system ringtone OR custom ToneSource tone
- Busy signal: **Yes, with visual too** — audio busy tone + UI indication
- Dial tone stops: **On first ring** (STATUS_RINGING signal from remote)

### Mute Implementation
- **Transmit mute only** — mute stops sending audio, still hear remote
- Mute **persists across profile switches** — sticky behavior
- Match Python LXST behavior for mute signalling to remote peer

### Claude's Discretion: Mute Location
- Claude decides where in pipeline mute happens (LineSource, Packetizer, or Mixer gain)
- Should balance CPU efficiency with code simplicity

</decisions>

<specifics>
## Specific Ideas

- "Do whatever Python LXST does" for mute signalling — maintain compatibility
- Kotlin is the authority; Python is the network transport layer
- Direct method calls for Kotlin→Python coordination (not signalling channel)

### Future-Proofing: Pure Kotlin Reticulum
**When in doubt, design as if Python+Chaquopy might be replaced with pure Kotlin Reticulum.**

This means:
- Abstract network transport behind a Kotlin interface (not coupled to Chaquopy)
- Telephone class should not import or reference PyObject directly
- Use a `NetworkTransport` interface that Python bridge implements today, Kotlin Reticulum implements later
- Keep signalling protocol clean — it should work over any transport
- Minimize Python-specific assumptions in call flow

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 11-telephony-integration*
*Context gathered: 2026-02-04*
