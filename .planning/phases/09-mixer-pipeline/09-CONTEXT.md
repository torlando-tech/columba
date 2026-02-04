# Phase 9: Mixer & Pipeline - Context

**Gathered:** 2026-02-04
**Status:** Ready for planning

<domain>
## Phase Boundary

Implement audio mixing infrastructure and pipeline orchestration in Kotlin. Mixer combines multiple sources (local mic + remote stream) with gain control. Pipeline wires source→codec→sink. ToneSource generates dial/busy tones with smooth fade. Must match Python LXST structure as closely as possible.

</domain>

<decisions>
## Implementation Decisions

### Mixer Input Handling
- **Push model** — Sources call mixer.handleFrame(), matching Python LXST Mixer.py pattern
- **2 sources max** — Local microphone + one remote stream (simple case for voice calls)
- **Both per-source and global gain** — Individual source balance + master output control
- Sample rate mismatch handling: Claude decides (match Python LXST behavior)

### Mute Behavior
- Separate receive vs transmit mute: Claude decides (match Python LXST Telephone)
- **Quick fade (10-20ms)** on mute transitions — smooth to avoid pops
- **Reset mute state each call** — user always starts unmuted
- Output during mute: Claude decides (match Python LXST pattern)

### ToneSource Patterns
- Tone types needed: Claude decides (match Python LXST Generators.py)
- Fade in/out timing: Claude decides (match Python LXST)
- **Match ITU-T standard frequencies** — 440Hz dial, 480/620Hz busy (familiar to users)
- Configurable gain: Claude decides (match Python LXST)

### Pipeline Wiring
- Dynamic reconfiguration: Claude decides (match Python LXST for profile switching)
- Error handling: Claude decides (match Python LXST reliability patterns)
- Active vs passive: Claude decides (match Python LXST Pipeline.py)
- State observation: Claude decides (match Python LXST pattern)

### Claude's Discretion
- Sample rate mismatch handling in mixer
- Separate vs global mute implementation
- Output behavior when muted (silence frames vs skip)
- Which tones to implement (dial, busy, ringback)
- Fade timing for tones
- ToneSource gain configurability
- Dynamic pipeline reconfiguration approach
- Pipeline error handling strategy
- Active vs passive pipeline role
- State observation mechanism

**Guiding principle:** When in doubt, match Python LXST implementation. This is a port, not a redesign.

</decisions>

<specifics>
## Specific Ideas

- Match Python LXST Mixer.py, Pipeline.py, Generators.py structure as closely as possible
- Reference Python code when making implementation decisions
- Keep the implementation patterns identical to Python where Kotlin allows

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 09-mixer-pipeline*
*Context gathered: 2026-02-04*
