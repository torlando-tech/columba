# Phase 8: Audio Sources & Sinks - Context

**Gathered:** 2026-02-04
**Status:** Ready for planning

<domain>
## Phase Boundary

Implement LineSource (microphone capture) and LineSink (speaker playback) in Kotlin, wrapping the existing KotlinAudioBridge. Sources output Float32 audio frames for encoding; sinks accept Float32 audio frames from decoding. Pipeline orchestration is Phase 9.

</domain>

<decisions>
## Implementation Decisions

### Bridge Integration
- **Wrap KotlinAudioBridge** — LineSource/LineSink use KotlinAudioBridge internally, adding LXST interface on top
- **Exclusive use** — All audio I/O goes through LineSource/LineSink; no direct bridge calls from other code
- **Kotlin Pipeline orchestrator** — New Kotlin Pipeline class (Phase 9) controls sources/sinks; Python only triggers start/stop
- **Keep old methods temporarily** — Old Python-facing KotlinAudioBridge methods remain but unused; safety net during migration

### Filter Chain Placement
- **Float32 throughout** — LineSource outputs Float32 [-1.0, 1.0], filters process Float32, encoder converts to int16 at the end
- Filter placement (inside source vs external): Claude matches Python LXST Sources.py pattern
- Filter configurability (per-call vs fixed): Claude matches Python LXST behavior
- Playback filters: Claude matches Python LXST Sinks.py behavior

### Buffer Sizing
- **Profile-adaptive** — ULL (ultra-low-latency) uses tiny buffers, HQ uses larger buffers; optimize per use case
- **Use Android low-latency mode** — AudioTrack.PERFORMANCE_MODE_LOW_LATENCY for ULL profiles when available
- **No exposed metrics** — Buffer fill level, underrun count kept internal; cleaner API
- Underrun handling: Claude implements standard audio handling (likely silence insertion)

### Codec Handoff
- **Codec is external** — LineSource doesn't know about codecs; Pipeline wires source → codec → network
- Pull vs push model: Claude matches Python LXST Source/Codec interaction pattern
- Frame sizing: Claude matches Python LXST frame sizing behavior
- LineSink input format: Claude matches Python LXST Sink/Codec separation

### Claude's Discretion
- Filter placement in the capture/playback chain (match Python LXST)
- Pull vs push model for codec feeding (match Python LXST)
- Exact frame sizing behavior (match Python LXST)
- LineSink input format — raw Float32 or encoded (match Python LXST)
- Underrun handling strategy

</decisions>

<specifics>
## Specific Ideas

- User wants to **match Python LXST file/method structure exactly** — this is a port, not a redesign
- Existing KotlinAudioFilters already work with <1ms latency; reuse them
- KotlinAudioBridge has proven ring buffer implementation; wrap don't rewrite

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 08-audio-sources-sinks*
*Context gathered: 2026-02-04*
