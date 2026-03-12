# Requirements: Columba v0.10.0 Voice Messages

**Defined:** 2026-02-23
**Core Value:** Reliable off-grid messaging with a polished, responsive user experience.

## v1 Requirements

Requirements for v0.10.0 release. Each maps to roadmap phases.

### Recording

- [x] **REC-01**: User can record a voice message by holding the mic button in chat input
- [ ] **REC-02**: Mic button replaces send button when text field is empty
- [ ] **REC-03**: User can cancel recording by sliding finger left (slide-to-cancel)
- [x] **REC-04**: Recording timer counts up from 0:00, visible during recording
- [x] **REC-05**: Recording auto-stops and auto-sends at 30-second limit
- [x] **REC-06**: Visual recording indicator (pulsing animation) during active recording
- [x] **REC-07**: Recordings shorter than 300ms are discarded (prevent accidental sends)
- [x] **REC-09**: Draft preview — listen to recording before sending (promoted from v2)

### Protocol & Transport

- [x] **PROTO-01**: Voice messages encode with Opus VOICE_MEDIUM (8kbps) via LXST-kt NativeCaptureEngine + Opus codec
- [x] **PROTO-02**: Encoded audio sent as FIELD_AUDIO (0x07) in LXMF message
- [x] **PROTO-03**: FIELD_AUDIO (0x07) / LEGACY_LOCATION_FIELD (7) collision resolved safely
- [x] **PROTO-04**: Waveform amplitude data stored alongside audio for rendering without re-decode
- [x] **PROTO-05**: Received FIELD_AUDIO extracted from LXMF message and stored in MessageEntity.fieldsJson

### Playback

- [x] **PLAY-01**: Voice messages render inline as audio bubble with play/pause button
- [x] **PLAY-02**: Waveform visualization shows audio shape in message bubble
- [x] **PLAY-03**: Waveform animates as progress indicator during playback
- [x] **PLAY-04**: Duration displayed in mm:ss format
- [ ] **PLAY-05**: Unplayed voice messages have visual indicator (distinct from played)
- [ ] **PLAY-06**: Only one voice message plays at a time (new play stops previous)
- [ ] **PLAY-07**: Playback uses LXST-kt Opus decode + Oboe native output

### Audio Focus & Edge Cases

- [x] **EDGE-01**: Recording requests AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE (silences notifications)
- [ ] **EDGE-02**: Playback requests AUDIOFOCUS_GAIN_TRANSIENT (ducks other audio)
- [ ] **EDGE-03**: Incoming call during recording stops and discards recording gracefully
- [ ] **EDGE-04**: App backgrounded during recording stops and discards
- [ ] **EDGE-05**: Headphone disconnect during playback pauses playback
- [x] **EDGE-06**: Corrupt/incomplete audio data shows error state in bubble (no crash)
- [x] **EDGE-07**: RECORD_AUDIO permission requested before first recording attempt

## v2 Requirements

Deferred to future release. Tracked but not in current roadmap.

### Playback Enhancements

- **PLAY-08**: Playback speed controls (1x / 1.5x / 2x toggle)
- **PLAY-09**: Remember playback position within session
- **PLAY-10**: Sequential auto-play of unplayed voice messages
- **PLAY-11**: Proximity sensor switches audio to earpiece when held to ear

### Recording Enhancements

- **REC-08**: Lock recording mode (swipe up on mic to record hands-free)

### System Integration

- **SYS-01**: Background/out-of-chat playback with mini-player bar
- **SYS-02**: Bluetooth audio routing for playback

## Out of Scope

| Feature | Reason |
|---------|--------|
| Voice-to-text transcription | Requires large ML models or cloud API; privacy-hostile for mesh messenger |
| Voice message forwarding | Same as sending a new message; no protocol change needed |
| Raise-to-speak recording | Fragile sensor fusion, high false-positive rate |
| Self-destructing voice messages | LXMF has no deletion protocol; false sense of security |
| Video notes (round video messages) | Video at mesh-compatible bitrates is unusable quality |
| Audio effects / voice filters | Unnecessary complexity; Opus VOIP mode has built-in signal processing |
| Generic audio file inline player | Voice messages (field 7) are distinct from file attachments (field 5) |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| REC-01 | Phase 8 | Complete |
| REC-02 | Phase 10 | Pending |
| REC-03 | Phase 10 | Pending |
| REC-04 | Phase 8 | Complete |
| REC-05 | Phase 8 | Complete |
| REC-06 | Phase 8 | Complete |
| REC-07 | Phase 8 | Complete |
| REC-09 | Phase 8 | Complete (promoted from v2) |
| PROTO-01 | Phase 7 | Complete |
| PROTO-02 | Phase 7 | Complete |
| PROTO-03 | Phase 7 | Complete |
| PROTO-04 | Phase 7 | Complete |
| PROTO-05 | Phase 7 | Complete |
| PLAY-01 | Phase 8 | Complete (moved from Phase 9) |
| PLAY-02 | Phase 8 | Complete (moved from Phase 9) |
| PLAY-03 | Phase 8 | Complete (moved from Phase 9) |
| PLAY-04 | Phase 8 | Complete (moved from Phase 9) |
| PLAY-05 | Phase 9 | Pending |
| PLAY-06 | Phase 9 | Pending |
| PLAY-07 | Phase 9 | Pending |
| EDGE-01 | Phase 8 | Complete |
| EDGE-02 | Phase 9 | Pending |
| EDGE-03 | Phase 10 | Pending |
| EDGE-04 | Phase 10 | Pending |
| EDGE-05 | Phase 10 | Pending |
| EDGE-06 | Phase 8 | Complete (moved from Phase 9) |
| EDGE-07 | Phase 8 | Complete |

**Coverage:**
- v1 requirements: 23 total (REC-09 promoted from v2)
- Complete: 18
- Pending: 5 (PLAY-05, PLAY-06, PLAY-07, EDGE-02..05, REC-02, REC-03)
- Mapped to phases: 23
- Unmapped: 0

---
*Requirements defined: 2026-02-23*
*Last updated: 2026-03-11 after Phase 8 completion*
