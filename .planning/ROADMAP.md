# Roadmap: Columba

## Milestones

- v0.7.3-beta (Phases 1-2.3) - shipped 2026-01-28
- v0.7.4-beta Bug Fixes (Phases 3-6) - partial (3-4 complete, 5-6 deferred)
- **v0.8.0 Kotlin LXST Audio Pipeline** (Phases 7-12) - in progress

## Phases

<details>
<summary>v0.7.3-beta (Phases 1-2.3) - SHIPPED 2026-01-28</summary>

See git history for v0.7.3-beta milestone.

</details>

<details>
<summary>v0.7.4-beta Bug Fixes (Phases 3-6) - PARTIAL</summary>

- [x] **Phase 3: ANR Elimination** - Fix synchronous IPC on main thread
- [x] **Phase 4: Relay Loop Resolution** - Investigate and fix COLUMBA-3 regression
- [ ] **Phase 5: Memory Optimization** - DEFERRED (infrastructure added)
- [ ] **Phase 6: Native Stability Verification** - DEFERRED

</details>

### v0.8.0 Kotlin LXST Audio Pipeline (In Progress)

**Milestone Goal:** Rewrite LXST audio pipeline in Kotlin, eliminating JNI bridge latency by passing only encoded packets to Python Reticulum.

- [x] **Phase 7: Codec Foundation** - Implement Opus and Codec2 codecs in Kotlin
- [x] **Phase 8: Audio Sources & Sinks** - Implement LineSource, LineSink with existing bridge
- [x] **Phase 9: Mixer & Pipeline** - Implement audio mixing and pipeline orchestration
- [x] **Phase 10: Network Bridge** - Connect Kotlin audio to Python Reticulum
- [ ] **Phase 11: Telephony Integration** - Port Telephone class and profiles
- [ ] **Phase 12: Quality Verification** - End-to-end testing and optimization

## Phase Details

### Phase 7: Codec Foundation
**Goal**: Kotlin Opus/Codec2 encode audio frames into wire-compatible packets
**Depends on**: Nothing (foundation phase)
**Requirements**: CODEC-01, CODEC-02, CODEC-03
**Success Criteria** (what must be TRUE):
  1. Opus packets from Kotlin are decodable by Python pyogg with intelligible audio
  2. Codec2 packets from Kotlin are decodable by Python pycodec2 with intelligible audio
  3. All 9 Opus profiles and 7 Codec2 modes work correctly
  4. Encoder/decoder round-trip preserves audio fidelity
**Plans**: 6 plans (3 implementation + 3 gap closure)

Plans:
- [x] 07-01-PLAN.md — Base Codec infrastructure (Null codec, resample utilities)
- [x] 07-02-PLAN.md — Opus codec with all 9 profiles
- [x] 07-03-PLAN.md — Codec2 codec with all 7 modes
- [x] 07-04-PLAN.md — [GAP CLOSURE] Opus instrumented tests (all 9 profiles)
- [x] 07-05-PLAN.md — [GAP CLOSURE] Codec2 instrumented tests (all 7 modes + headers)
- [x] 07-06-PLAN.md — [GAP CLOSURE] Round-trip fidelity and bitrate ceiling validation

### Phase 8: Audio Sources & Sinks
**Goal**: LineSource captures mic audio, LineSink plays to speaker, both in Kotlin wrapping existing KotlinAudioBridge
**Depends on**: Phase 7 (needs codecs for encode/decode)
**Requirements**: SOURCE-01, SOURCE-02, SINK-01, SINK-02, QUAL-02
**Success Criteria** (what must be TRUE):
  1. LineSource captures audio from microphone using existing KotlinAudioBridge
  2. LineSource applies filters (using KotlinAudioFilters) with <1ms latency
  3. LineSink plays audio with buffer management (no underruns on normal operation)
  4. LineSink handles low-latency mode
**Plans**: 4 plans

Plans:
- [x] 08-01-PLAN.md — Base interfaces (Source, LocalSource, Sink, LocalSink) + data conversion helpers
- [x] 08-02-PLAN.md — LineSource implementation wrapping KotlinAudioBridge
- [x] 08-03-PLAN.md — LineSink implementation with queue-based playback
- [x] 08-04-PLAN.md — Unit tests for configuration logic (mocked bridge/codec)

### Phase 9: Mixer & Pipeline
**Goal**: Multiple audio sources mix correctly, pipeline orchestrates flow
**Depends on**: Phase 8 (needs sources/sinks)
**Requirements**: MIX-01, MIX-02, PIPE-01, GEN-01
**Success Criteria** (what must be TRUE):
  1. Mixer combines frames from multiple sources with gain control
  2. Mute/unmute works for both receive and transmit paths
  3. Pipeline correctly wires source->codec->sink
  4. ToneSource generates dial tones with smooth fade in/out
**Plans**: 4 plans

Plans:
- [x] 09-01-PLAN.md — Mixer implementation (multi-source combining with gain control)
- [x] 09-02-PLAN.md — ToneSource implementation (sine wave generator with fade in/out)
- [x] 09-03-PLAN.md — Pipeline implementation (component orchestration wrapper)
- [x] 09-04-PLAN.md — Unit tests for configuration logic (mocked dependencies)

### Phase 10: Network Bridge
**Goal**: Encoded packets flow between Kotlin and Python Reticulum
**Depends on**: Phase 9 (needs complete pipeline)
**Requirements**: NET-01, NET-02, NET-03, BRIDGE-01, BRIDGE-02
**Success Criteria** (what must be TRUE):
  1. Kotlin Packetizer sends encoded frames to Python via Chaquopy
  2. Python LinkSource receives encoded frames and passes to Kotlin
  3. Signalling (call status, profile changes) works bidirectionally
  4. Bridge latency under 5ms for packet transfer
  5. Encoded packets are <100 bytes (typical: 20-60 bytes)
**Plans**: 5 plans

Plans:
- [x] 10-01-PLAN.md — NetworkPacketBridge (low-level Kotlin-Python packet coordination)
- [x] 10-02-PLAN.md — Packetizer (RemoteSink, Kotlin->Python audio transmission)
- [x] 10-03-PLAN.md — LinkSource (RemoteSource, Python->Kotlin audio reception)
- [x] 10-04-PLAN.md — SignallingReceiver (bidirectional call signalling)
- [x] 10-05-PLAN.md — Integration with call_manager.py and PythonWrapperManager

### Phase 11: Telephony Integration
**Goal**: Telephone class fully works with Kotlin audio backend
**Depends on**: Phase 10 (needs network bridge)
**Requirements**: TEL-01, TEL-02, TEL-03, TEL-04, TEL-05
**Success Criteria** (what must be TRUE):
  1. All 8 quality profiles (ULBW through ULL) work
  2. Outgoing calls connect with dial tone feedback
  3. Incoming calls ring (using ringtone if configured)
  4. Mid-call profile switching works
  5. Mute controls work correctly
**Plans**: 5 plans

Plans:
- [x] 11-01-PLAN.md — Profile sealed class and NetworkTransport interface
- [x] 11-02-PLAN.md — Telephone core (state machine, call/answer/hangup, mute)
- [x] 11-03-PLAN.md — Audio feedback (dial tone, busy tone, ringtone)
- [x] 11-04-PLAN.md — Python integration (call_manager wiring, PythonWrapperManager)
- [x] 11-05-PLAN.md — Unit tests (Profile, Telephone configuration logic)

**VERIFICATION STATUS: GAPS_FOUND** — See 11-VERIFICATION.md for critical blockers

### Phase 11.5: Signal Bridge Fix (GAP CLOSURE)
**Goal:** Wire INTEGER signals from Python LXST to Kotlin Telephone
**Depends on:** Phase 11 (uses existing NetworkPacketBridge)
**Requirements:** NET-03
**Gap Closure:** Fixes signal type mismatch (Python STRING → Kotlin INTEGER)
**Success Criteria** (what must be TRUE):
  1. NetworkPacketBridge has `receiveSignal(int)` method callable from Python
  2. Python call_manager forwards LXST signals as integers to Kotlin
  3. Kotlin logs show `Signal received: 0x06` when call establishes
  4. Kotlin Telephone transitions through CONNECTING → ESTABLISHED

Plans:
- [x] 11.5-01-PLAN.md — Wire integer signals from Python to Kotlin

### Phase 11.6: Python Audio Disable (GAP CLOSURE)
**Goal:** Disable Python LXST audio when Kotlin LXST handles audio
**Depends on:** Phase 11.5 (signals must work first)
**Requirements:** BRIDGE-01, QUAL-01
**Gap Closure:** Eliminates dual audio pipeline conflict
**Success Criteria** (what must be TRUE):
  1. Python LXST has `set_kotlin_audio_active(bool)` method
  2. Kotlin Telephone calls this when opening/closing pipelines
  3. No Python audio logs (`📡 PKT.tx`, `🎤 LS:`) during Kotlin calls
  4. No AudioFlinger BUFFER TIMEOUT errors during calls

Plans:
- [x] 11.6-01-PLAN.md — Disable Python audio when Kotlin active

**VERIFICATION STATUS: SUPERSEDED** — Phase 11.6 approach superseded by Phase 11.7 (LXST removal)

### Phase 11.7: Remove Python LXST
**Goal:** Remove Python LXST dependency; rewrite call_manager.py to use raw Reticulum link APIs with msgpack wire protocol, making Python a pure network transport
**Depends on:** Phase 11 (needs Kotlin Telephone, codecs, pipelines)
**Supersedes:** Phase 11.6 (flag-based audio disable — band-aid for dual-pipeline problem)
**Requirements:** TEL-01, NET-01, NET-02, NET-03, BRIDGE-01, BRIDGE-02
**Success Criteria** (what must be TRUE):
  1. Python LXST library is not imported or used anywhere in the codebase
  2. call_manager.py uses raw Reticulum Link APIs (RNS.Link, RNS.Packet) for call setup/teardown
  3. Audio packets use LXST-compatible msgpack wire format: `{0x01: [codec_byte + frame]}`
  4. Signalling uses LXST-compatible msgpack wire format: `{0x00: [signal_byte]}`
  5. Outgoing and incoming calls connect with audible audio (no dual-pipeline conflict)
  6. Calls interoperate with Sideband (same wire protocol)

**Plans:** 2 plans

Plans:
- [ ] 11.7-01-PLAN.md — Rewrite call_manager.py with raw Reticulum Link APIs, delete instrumentation, clean audio backend
- [ ] 11.7-02-PLAN.md — Remove Phase 11.6 Kotlin code, remove LXST from build, compile and test

### Phase 12: Quality Verification
**Goal**: Voice calls work smoothly on LAN without artifacts
**Depends on**: Phase 11 (needs full integration)
**Requirements**: QUAL-01, QUAL-03, QUAL-04, AUDIO-07
**Success Criteria** (what must be TRUE):
  1. LAN calls have no audible pops or delays
  2. Encode/decode latency under 5ms per frame
  3. End-to-end audio latency under 200ms on LAN
  4. 10-minute call runs without audio degradation
**Plans**: TBD

## Progress

**Execution Order:** Phases execute in numeric order: 7 -> 8 -> 9 -> 10 -> 11 -> 12

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 3. ANR Elimination | 1/1 | Complete | 2026-01-29 |
| 4. Relay Loop Resolution | 1/1 | Complete | 2026-01-29 |
| 5. Memory Optimization | 1/3 | Deferred | - |
| 6. Native Stability Verification | 0/1 | Deferred | - |
| 7. Codec Foundation | 6/6 | Complete | 2026-02-04 |
| 8. Audio Sources & Sinks | 4/4 | Complete | 2026-02-04 |
| 9. Mixer & Pipeline | 4/4 | Complete | 2026-02-04 |
| 10. Network Bridge | 5/5 | Complete | 2026-02-04 |
| 11. Telephony Integration | 5/5 | Gaps found | 2026-02-05 |
| 11.5 Signal Bridge Fix | 1/1 | Complete | 2026-02-05 |
| 11.6 Python Audio Disable | 1/1 | Superseded | 2026-02-05 |
| 11.7 Remove Python LXST | 0/2 | Planned | - |
| 12. Quality Verification | 0/? | Not started | - |
