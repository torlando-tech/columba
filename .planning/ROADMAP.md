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
- [ ] **Phase 8: Audio Sources & Sinks** - Implement LineSource, LineSink with existing bridge
- [ ] **Phase 9: Mixer & Pipeline** - Implement audio mixing and pipeline orchestration
- [ ] **Phase 10: Network Bridge** - Connect Kotlin audio to Python Reticulum
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
- [ ] 08-01-PLAN.md — Base interfaces (Source, LocalSource, Sink, LocalSink) + data conversion helpers
- [ ] 08-02-PLAN.md — LineSource implementation wrapping KotlinAudioBridge
- [ ] 08-03-PLAN.md — LineSink implementation with queue-based playback
- [ ] 08-04-PLAN.md — Unit tests for configuration logic (mocked bridge/codec)

### Phase 9: Mixer & Pipeline
**Goal**: Multiple audio sources mix correctly, pipeline orchestrates flow
**Depends on**: Phase 8 (needs sources/sinks)
**Requirements**: MIX-01, MIX-02, PIPE-01, GEN-01
**Success Criteria** (what must be TRUE):
  1. Mixer combines frames from multiple sources with gain control
  2. Mute/unmute works for both receive and transmit paths
  3. Pipeline correctly wires source->codec->sink
  4. ToneSource generates dial tones with smooth fade in/out
**Plans**: TBD

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
**Plans**: TBD

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
**Plans**: TBD

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
| 8. Audio Sources & Sinks | 0/4 | Not started | - |
| 9. Mixer & Pipeline | 0/? | Not started | - |
| 10. Network Bridge | 0/? | Not started | - |
| 11. Telephony Integration | 0/? | Not started | - |
| 12. Quality Verification | 0/? | Not started | - |
