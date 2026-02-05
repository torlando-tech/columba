---
phase: 11
plan: 02
subsystem: telephony
tags:
  - telephone
  - state-machine
  - network-transport
  - python-bridge
  - mute-persistence
dependency-graph:
  requires:
    - "11-01: Profile and NetworkTransport interface"
    - "10-01: NetworkPacketBridge"
    - "09-01: Mixer"
    - "09-02: ToneSource"
    - "08-01: LineSource and LineSink"
  provides:
    - "PythonNetworkTransport implementing NetworkTransport"
    - "Telephone class as main call controller"
    - "MixerSinkAdapter for Kotlin single inheritance workaround"
  affects:
    - "11-03: Full pipeline wiring and verification"
    - "Future: Pure Kotlin Reticulum (NetworkTransport interface ready)"
tech-stack:
  added: []
  patterns:
    - "Adapter pattern for Mixer as Sink"
    - "Dispatchers.IO for Python calls"
    - "State machine with signal-driven transitions"
key-files:
  created:
    - "reticulum/src/main/java/com/lxmf/messenger/reticulum/call/telephone/PythonNetworkTransport.kt"
    - "reticulum/src/main/java/com/lxmf/messenger/reticulum/call/telephone/Telephone.kt"
  modified: []
decisions:
  - id: "mixer-sink-adapter"
    choice: "MixerSinkAdapter wraps Mixer to provide Sink interface"
    rationale: "Kotlin single inheritance - Mixer extends LocalSource, needs adapter to act as Sink for ToneSource/LineSource/LinkSource"
  - id: "dispatchers-io-python"
    choice: "All Python calls use Dispatchers.IO"
    rationale: "Avoid blocking audio thread on Python GIL"
  - id: "internal-link-state"
    choice: "PythonNetworkTransport tracks link state internally"
    rationale: "isLinkActive property needed by Telephone, Python state harder to query"
metrics:
  duration: "~5 minutes"
  completed: "2026-02-04"
---

# Phase 11 Plan 02: PythonNetworkTransport and Telephone Summary

PythonNetworkTransport wraps Python call_manager with NetworkTransport interface. Telephone class owns call state machine, profile management, and audio pipeline lifecycle. MixerSinkAdapter bridges Kotlin single inheritance for source-to-mixer wiring.

## What Changed

### PythonNetworkTransport.kt

Implements NetworkTransport interface wrapping Python call_manager via Chaquopy:

| Method | Implementation |
|--------|----------------|
| `establishLink()` | Calls callManager.call(destinationHashHex) with Dispatchers.IO |
| `teardownLink()` | Calls callManager.hangup() |
| `sendPacket()` | Delegates to NetworkPacketBridge.sendPacket() |
| `sendSignal()` | Delegates to NetworkPacketBridge.sendSignal() |
| `setPacketCallback()` | Delegates to NetworkPacketBridge |
| `setSignalCallback()` | Delegates to NetworkPacketBridge |
| `isLinkActive` | Internal tracking (set on establish/teardown) |

Key design: This is the ONLY place PyObject appears in telephony code. Telephone uses NetworkTransport interface, not PyObject directly. Enables future pure-Kotlin Reticulum.

### Telephone.kt

Main call controller matching Python LXST Telephony.py:

**State Machine:**
```
STATUS_AVAILABLE (0x03) -- call() --> STATUS_CALLING (0x02)
STATUS_CALLING -- signal RINGING --> STATUS_RINGING (0x04)
STATUS_RINGING -- signal CONNECTING --> STATUS_CONNECTING (0x05)
STATUS_CONNECTING -- signal ESTABLISHED --> STATUS_ESTABLISHED (0x06)
```

**Public API:**

| Method | Purpose |
|--------|---------|
| `call(destinationHash, profile)` | Initiate outgoing call |
| `answer()` | Answer incoming call |
| `hangup(reason?)` | End call with optional reason |
| `switchProfile(profile)` | Mid-call profile change |
| `muteTransmit(mute)` | Mute/unmute microphone |
| `muteReceive(mute)` | Mute/unmute speaker |

**Pipeline Management:**

- `prepareDiallingPipelines()`: Create receiveMixer, audioOutput, dialTone
- `openPipelines()`: Add transmitMixer, packetizer, audioInput, linkSource
- `startPipelines()`: Start all components
- `stopPipelines()`: Stop all components
- `reconfigureTransmitPipeline()`: Recreate transmit path with new codec

**Mute Persistence (CONTEXT.md requirement):**
```kotlin
fun reconfigureTransmitPipeline() {
    val wasMuted = transmitMuted
    // ... recreate pipeline ...
    transmitMixer?.mute(wasMuted)  // Restore mute state
}
```

### MixerSinkAdapter

Bridges Kotlin single inheritance limitation:

```kotlin
class MixerSinkAdapter(private val mixer: Mixer) : LocalSink() {
    override fun canReceive(fromSource: Source?) = mixer.canReceive(fromSource)
    override fun handleFrame(frame: FloatArray, source: Source?) = mixer.handleFrame(frame, source)
    override fun start() = mixer.start()
    override fun stop() = mixer.stop()
    override fun isRunning() = mixer.isRunning()
}
```

Why needed:
- Python Mixer inherits from both LocalSource AND LocalSink
- Kotlin Mixer extends LocalSource only (single inheritance)
- Sources (ToneSource, LineSource, LinkSource) expect `sink: Sink?`
- Adapter wraps Mixer to provide Sink interface

## Deviations from Plan

### [Rule 3 - Blocking] MixerSinkAdapter added

**Found during:** Task 2 compilation
**Issue:** ToneSource.sink, LineSource.sink, LinkSource.sink expect `Sink?` type, but Mixer extends `LocalSource`, not `Sink`
**Fix:** Created MixerSinkAdapter that wraps Mixer and extends LocalSink, delegating all methods to the underlying Mixer
**Files modified:** Telephone.kt (added adapter class and adapter fields)
**Commit:** 411a1fb8

## Decisions Made

1. **MixerSinkAdapter pattern** - Wraps Mixer to provide Sink interface. This is the cleanest solution for Kotlin's single inheritance limitation without modifying existing Sink/Mixer classes.

2. **Dispatchers.IO for all Python calls** - establishLink() and teardownLink() use withContext(Dispatchers.IO) or runCatching to avoid blocking audio thread on Python GIL.

3. **Internal link state tracking** - PythonNetworkTransport tracks linkActive internally rather than querying Python. Simpler and faster for Telephone to check isLinkActive.

## Commits

| Hash | Message |
|------|---------|
| 1f6c3682 | feat(11-02): create PythonNetworkTransport wrapping Python bridge |
| 411a1fb8 | feat(11-02): create Telephone class as main call controller |

## Verification

- [x] PythonNetworkTransport implements NetworkTransport interface
- [x] Telephone compiles with all public methods
- [x] Telephone does NOT import PyObject directly
- [x] State machine uses same constants as Python LXST (Signalling object)
- [x] muteTransmit stores state and calls Mixer.mute()
- [x] switchProfile restores mute state after pipeline reconfiguration

PyObject check:
```bash
$ grep -c "PyObject" Telephone.kt
1  # Only in a comment: "Telephone does NOT import PyObject directly"
$ grep "import.*PyObject" Telephone.kt
# (no output - no PyObject import)
```

## Next Phase Readiness

**Ready for 11-03 (Full Pipeline Wiring):**
- Telephone class ready for integration testing
- PythonNetworkTransport ready to connect to Python call_manager
- Pipeline components created and wired correctly

**Integration points for 11-03:**
- Wire PythonNetworkTransport to actual call_manager PyObject
- Test Telephone.call() -> Python link establishment
- Test signal flow: Python -> NetworkPacketBridge -> Telephone
- Verify dial tone plays during ringing
- Verify audio flows after STATUS_ESTABLISHED
